package com.akine.billing.infrastructure;

import com.akine.billing.domain.PermissionCodes;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Lo que {@code billing} aporta al reporte economico (RF-M23-004).
 *
 * <h2>Son CINCO conceptos y se muestran como cinco</h2>
 *
 * <p>Deuda (M18), cobro (M19), caja (M20), presentacion a financiador (M21) y egreso (M22) son
 * cinco cosas distintas. Esta seccion publica <b>nueve indicadores con nueve fuentes declaradas y
 * ningun total que los sume</b>, y la prohibicion vale tambien para las etapas futuras.
 *
 * <p>El error concreto que esto evita, dicho sin eufemismos: un campo "ingresos totales" que sume
 * {@code cobrado} con {@code caja-ingresos-efectivo} <b>cuenta dos veces cada cobro en
 * efectivo</b>, porque el movimiento de caja de origen {@code COBRO} <i>es</i> ese cobro visto
 * desde el cajon. Y la relacion ni siquiera es uno a uno —un cobro con dos medios produce dos
 * movimientos, uno con tarjeta produce uno que no afecta el arqueo—, asi que tampoco se arregla
 * dividiendo por dos.
 *
 * <h2>Reconciliar es comparar, no sumar</h2>
 *
 * <p>El plan pide que los totales economicos reconcilien. Reconciliar es poner dos numeros que
 * <b>deberian coincidir</b> uno al lado del otro y mostrar la resta:
 *
 * <ul>
 *   <li>{@code conciliacion-cobrado-efectivo} — lo cobrado por medio efectivo, desde M19;</li>
 *   <li>{@code conciliacion-caja-de-cobros} — lo que entro al cajon con origen {@code COBRO},
 *       desde M20;</li>
 *   <li>{@code conciliacion-diferencia} — la resta. <b>Deberia ser cero.</b></li>
 * </ul>
 *
 * <p>Si no da cero, hay plata cobrada en efectivo que no entro a ninguna caja, o al reves. Es la
 * unica parte del reporte donde dos fuentes se combinan, y se combinan restando, declarando que se
 * restan, y esperando cero.
 *
 * <h2>Dos clases de corte, y las dos declaradas</h2>
 *
 * <p>Las obligaciones, los cobros y los egresos cortan por <b>instante</b>, contra la ventana ya
 * proyectada a la zona de la sede. La caja corta por <b>{@code fecha_negocio}</b>, que ya es una
 * fecha local de la sede y se compara directo. Mezclarlas imputaria un movimiento al dia
 * equivocado y el arqueo no cerraria por una razon invisible, que es exactamente lo que 07.03 se
 * cuido de evitar.
 */
@Component
public class EconomiaEnElReporte implements ReporteContributor {

	private static final String SECCION = "economia";
	private static final String MONEDA_POR_DEFECTO = "ARS";

	private static final String CRITERIO_INSTANTE_SEDE =
			"instante proyectado a la zona de la sede";
	private static final String CRITERIO_FECHA_NEGOCIO =
			"fecha_negocio, que ya es una fecha local de la sede";

	private final ObligacionRepository obligaciones;
	private final CobroRepository cobros;
	private final MovimientoCajaRepository movimientos;
	private final JornadaCajaRepository jornadas;
	private final EgresoRepository egresos;

	public EconomiaEnElReporte(
			ObligacionRepository obligaciones,
			CobroRepository cobros,
			MovimientoCajaRepository movimientos,
			JornadaCajaRepository jornadas,
			EgresoRepository egresos) {

		this.obligaciones = obligaciones;
		this.cobros = cobros;
		this.movimientos = movimientos;
		this.jornadas = jornadas;
		this.egresos = egresos;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.ECONOMICO, ReporteCode.OPERATIVO);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Economia";
	}

	/**
	 * {@code cobro:register}, el mismo con el que {@code EconomiaEnElResumenDePersona} ya deja leer
	 * la cuenta corriente. No se inventa un {@code economia:read} que la matriz no tiene: una etapa
	 * no amplia la matriz de permisos.
	 */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.COBRO_REGISTER;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		// --- M18: la deuda ------------------------------------------------------------
		BigDecimal devengado = cero(obligaciones.sumarDevengadoEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante()));
		BigDecimal devengadoAnulado = cero(obligaciones.sumarAnuladoEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante()));
		BigDecimal deudaVigente = cero(obligaciones.sumarSaldoVigenteEnElReporte(org, sede));

		// --- M19: el cobro ------------------------------------------------------------
		BigDecimal cobrado = cero(cobros.sumarCobradoEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante()));
		BigDecimal cobradoEfectivo = cero(cobros.sumarCobradoPorMedioEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante(), "EFECTIVO"));

		// --- M20: la caja -------------------------------------------------------------
		BigDecimal cajaIngresos = cero(movimientos.sumarEfectivoPorTipoEnElReporte(
				org, sede, consulta.desde(), consulta.hasta(), "INGRESO"));
		BigDecimal cajaEgresos = cero(movimientos.sumarEfectivoPorTipoEnElReporte(
				org, sede, consulta.desde(), consulta.hasta(), "EGRESO"));
		BigDecimal cajaDeCobros = cero(movimientos.sumarEfectivoDeCobrosEnElReporte(
				org, sede, consulta.desde(), consulta.hasta()));
		BigDecimal diferenciasDeArqueo = cero(jornadas.sumarDiferenciasEnElReporte(
				org, sede, consulta.desde(), consulta.hasta()));

		// --- M22: el egreso -----------------------------------------------------------
		BigDecimal egresosConfirmados = cero(egresos.sumarConfirmadosEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante()));
		BigDecimal egresosPendientes = cero(egresos.sumarSaldoPendienteEnElReporte(org, sede));

		String moneda = MONEDA_POR_DEFECTO;

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.dinero("devengado", "Producido (devengado)", devengado, moneda,
						"M18 obligacion",
						"devengada_en, " + CRITERIO_INSTANTE_SEDE
								+ "; excluye ANULADA y la baja logica"),
				IndicadorDeReporte.dinero("devengado-anulado", "Devengado anulado",
						devengadoAnulado, moneda, "M18 obligacion",
						"devengada_en, " + CRITERIO_INSTANTE_SEDE + "; solo ANULADA"),
				// El saldo es un derivado materializado que refleja HOY, no el dia de corte.
				// Reconstruirlo a una fecha exigiria restar las imputaciones posteriores, que es
				// una segunda formula de la misma cosa. Se declara en vez de fingirse exacto.
				IndicadorDeReporte.dinero("deuda-vigente", "Deuda vigente", deudaVigente, moneda,
						"M18 obligacion",
						"saldo actual, NO reconstruido a la fecha de corte; PENDIENTE y PARCIAL"),
				IndicadorDeReporte.dinero("cobrado", "Cobrado", cobrado, moneda, "M19 cobro",
						"cobrado_en, " + CRITERIO_INSTANTE_SEDE + "; excluye la baja logica"),
				IndicadorDeReporte.dinero("caja-ingresos-efectivo", "Ingresos de caja (efectivo)",
						cajaIngresos, moneda, "M20 movimiento_caja",
						CRITERIO_FECHA_NEGOCIO + "; tipo INGRESO, solo lo que afecta el arqueo"),
				IndicadorDeReporte.dinero("caja-egresos-efectivo", "Egresos de caja (efectivo)",
						cajaEgresos, moneda, "M20 movimiento_caja",
						CRITERIO_FECHA_NEGOCIO + "; tipo EGRESO, solo lo que afecta el arqueo"),
				IndicadorDeReporte.dinero("caja-diferencias-de-arqueo", "Diferencias de arqueo",
						diferenciasDeArqueo, moneda, "M20 jornada_caja",
						CRITERIO_FECHA_NEGOCIO + "; solo jornadas CERRADAS"),
				IndicadorDeReporte.dinero("egresos-confirmados", "Egresos confirmados",
						egresosConfirmados, moneda, "M22 egreso",
						"registrado_en, " + CRITERIO_INSTANTE_SEDE
								+ "; estados CONFIRMADO y PAGADO"),
				IndicadorDeReporte.dinero("egresos-saldo-pendiente", "Egresos por pagar",
						egresosPendientes, moneda, "M22 egreso",
						"saldo actual, NO reconstruido a la fecha de corte; solo CONFIRMADO"),

				// --- La reconciliacion: dos numeros y su resta, nunca una suma ------------
				IndicadorDeReporte.dinero("conciliacion-cobrado-efectivo",
						"Cobrado en efectivo (M19)", cobradoEfectivo, moneda, "M19 cobro_medio",
						"cobrado_en, " + CRITERIO_INSTANTE_SEDE + "; medio EFECTIVO"),
				IndicadorDeReporte.dinero("conciliacion-caja-de-cobros",
						"Entrado al cajon por cobros (M20)", cajaDeCobros, moneda,
						"M20 movimiento_caja",
						CRITERIO_FECHA_NEGOCIO + "; tipo_origen COBRO, afecta el arqueo"),
				IndicadorDeReporte.dinero("conciliacion-diferencia",
						"Diferencia de conciliacion (deberia ser cero)",
						cobradoEfectivo.subtract(cajaDeCobros), moneda,
						"M19 cobro_medio menos M20 movimiento_caja",
						"resta de los dos anteriores; distinta de cero significa plata cobrada "
								+ "en efectivo que no entro a ninguna caja, o al reves"));

		return AporteDeReporte.de(SECCION, titulo(), indicadores);
	}

	/**
	 * Un {@code SUM} sin filas devuelve {@code null}, no cero.
	 *
	 * <p>Y un {@code null} que llega a la pantalla se lee como "sin dato" cuando en realidad dice
	 * "no hubo movimiento". Son dos cosas distintas y solo una es cierta.
	 */
	private static BigDecimal cero(BigDecimal valor) {
		return valor == null ? BigDecimal.ZERO : valor;
	}
}
