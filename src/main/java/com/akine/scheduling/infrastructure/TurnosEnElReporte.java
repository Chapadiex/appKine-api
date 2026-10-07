package com.akine.scheduling.infrastructure;

import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lo que {@code scheduling} aporta a los reportes: volumen, estados, ausentismo y
 * reprogramaciones (RF-M23-002).
 *
 * <h2>Turno es reserva, no atencion</h2>
 *
 * <p>Esta seccion cuenta <b>reservas</b>. La cantidad de atenciones que realmente ocurrieron la
 * aporta {@code encounter} en su propia seccion, y las dos conviven sin fundirse porque DP-05 lo
 * exige: <b>ninguna transicion administrativa prueba por si sola que una prestacion ocurrio</b>.
 * Un turno confirmado del que nadie abrio la sesion no es una atencion, y sumarlo como tal seria
 * inflar la produccion del centro con una promesa.
 *
 * <h2>Por que las reprogramaciones no salen de {@code turno_evento}</h2>
 *
 * <p>Salen de {@code turno.reprogramado_en}, que es una columna del propio turno y cae dentro del
 * rango que {@code ix_turno_sede_dia} ya cubre. Contarlas desde el append-only obligaria a un
 * indice nuevo sobre una tabla que crece sin techo, <b>para responder exactamente lo mismo</b>.
 *
 * <h2>El corte</h2>
 *
 * <p>Por {@code inicio}, que es cuando el turno <i>iba a ocurrir</i>, y no por {@code reservado_en},
 * que es cuando se pidio. Un reporte de septiembre tiene que traer los turnos de septiembre, no los
 * que se reservaron en septiembre para diciembre. La ventana viene ya proyectada a la zona de la
 * sede: hacer la conversion aca seria la cuarta oportunidad de usar la del servidor.
 */
@Component
public class TurnosEnElReporte implements ReporteContributor {

	private static final String SECCION = "turnos";
	private static final String FUENTE = "M12 turno";
	private static final String CRITERIO = "inicio, instante proyectado a la zona de la sede; "
			+ "excluye los dados de baja";

	private final TurnoRepository turnos;

	public TurnosEnElReporte(TurnoRepository turnos) {
		this.turnos = turnos;
	}

	@Override
	public Set<ReporteCode> reportes() {
		// Alimenta los dos: calcular el mismo numero dos veces con dos formulas seria exactamente
		// la divergencia que esta etapa se propuso no crear.
		return Set.of(ReporteCode.TURNOS, ReporteCode.OPERATIVO);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Turnos";
	}

	/** {@code turno:read}: el mismo con el que se lee la agenda. Una etapa no amplia la matriz. */
	@Override
	public String permisoRequerido() {
		return "turno:read";
	}

	/**
	 * Sabe contar solo los turnos del profesional que pide el reporte (G-1, DP-15): son los que
	 * tienen su membership en {@code profesional_membership_id}.
	 */
	@Override
	public boolean filtraPorActividadPropia() {
		return true;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		Map<EstadoTurnoClave, Long> porEstado = new EnumMap<>(EstadoTurnoClave.class);
		for (EstadoTurnoClave clave : EstadoTurnoClave.values()) {
			porEstado.put(clave, 0L);
		}

		long total = 0;
		List<FilaDeReporte> filas = new ArrayList<>();

		for (Object[] fila : turnos.contarPorDiaYEstadoEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(),
				consulta.zona().getId(), consulta.limiteFilas(),
				consulta.recortadaAActividadPropia(), consulta.membershipsDelRecorte())) {

			String dia = String.valueOf(fila[0]);
			String estado = String.valueOf(fila[1]);
			long cantidad = ((Number) fila[2]).longValue();

			total += cantidad;
			EstadoTurnoClave clave = EstadoTurnoClave.desde(estado);
			if (clave != null) {
				porEstado.merge(clave, cantidad, Long::sum);
			}
			filas.add(FilaDeReporte.de(dia, estado, String.valueOf(cantidad)));
		}

		long reprogramados = turnos.contarReprogramadosEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(),
				consulta.recortadaAActividadPropia(), consulta.membershipsDelRecorte());

		long cancelados = porEstado.get(EstadoTurnoClave.CANCELADO);
		long ausentes = porEstado.get(EstadoTurnoClave.AUSENTE);

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.contando("turnos-totales", "Turnos", total, FUENTE, CRITERIO),
				IndicadorDeReporte.contando("turnos-reservados", "Reservados",
						porEstado.get(EstadoTurnoClave.RESERVADO), FUENTE, CRITERIO),
				IndicadorDeReporte.contando("turnos-confirmados", "Confirmados",
						porEstado.get(EstadoTurnoClave.CONFIRMADO), FUENTE, CRITERIO),
				IndicadorDeReporte.contando("turnos-en-espera", "En espera",
						porEstado.get(EstadoTurnoClave.EN_ESPERA), FUENTE, CRITERIO),
				IndicadorDeReporte.contando(
						"turnos-cancelados", "Cancelados", cancelados, FUENTE, CRITERIO),
				IndicadorDeReporte.contando(
						"turnos-ausentes", "Ausentes", ausentes, FUENTE, CRITERIO),
				IndicadorDeReporte.contando("turnos-reprogramados", "Reprogramados", reprogramados,
						FUENTE, "reprogramado_en no nulo, sobre turnos del periodo"),
				IndicadorDeReporte.porcentaje("tasa-ausentismo", "Ausentismo",
						tasaDeAusentismo(total, cancelados, ausentes),
						FUENTE, "ausentes sobre turnos del periodo que no fueron cancelados"));

		return new AporteDeReporte(
				SECCION, titulo(), indicadores,
				List.of("Dia", "Estado", "Turnos"), filas, List.of());
	}

	/**
	 * Ausentes sobre los turnos que el paciente podria haber cumplido.
	 *
	 * <p>El denominador <b>descuenta los cancelados</b>: una cancelacion avisada no es un
	 * ausentismo, y meterla abajo diluye el indicador justo en el centro que mas cancela. Y cuando
	 * el denominador es cero devuelve <b>cero explicito</b>, no una division que explota ni un
	 * {@code null} que la pantalla interpretaria como "sin dato".
	 */
	private static BigDecimal tasaDeAusentismo(long total, long cancelados, long ausentes) {
		long base = total - cancelados;
		if (base <= 0) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(ausentes)
				.multiply(BigDecimal.valueOf(100))
				.divide(BigDecimal.valueOf(base), 2, RoundingMode.HALF_UP);
	}

	/**
	 * Los estados que esta seccion reporta.
	 *
	 * <p>Se declara aparte de {@code EstadoTurno} a proposito: el reporte tiene que <b>seguir
	 * contando</b> un estado que todavia no conoce en vez de romperse, asi que la consulta agrupa
	 * por el texto de la columna y esto solo mapea los que tienen indicador propio.
	 */
	private enum EstadoTurnoClave {
		RESERVADO, CONFIRMADO, CANCELADO, AUSENTE, EN_ESPERA;

		static EstadoTurnoClave desde(String nombre) {
			for (EstadoTurnoClave clave : values()) {
				if (clave.name().equals(nombre)) {
					return clave;
				}
			}
			return null;
		}
	}
}
