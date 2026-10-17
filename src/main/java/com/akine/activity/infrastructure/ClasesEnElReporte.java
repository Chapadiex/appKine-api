package com.akine.activity.infrastructure;

import com.akine.activity.application.CapacidadDeClase;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.PermissionCodes;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lo que {@code activity} aporta al reporte de clases: cuantas se programaron, se dictaron y se
 * cancelaron, y su ocupacion (RF-M23-007).
 *
 * <h2>La ocupacion es sobre la capacidad EFECTIVA</h2>
 *
 * <p>RN-M23-007 lo dice con todas las letras y V58 lo explica: la capacidad de una clase es el
 * minimo entre la propia, la de la oferta y la del espacio, y <b>se calcula al leer</b>. Esta
 * seccion no tiene una formula propia: llama a {@link CapacidadDeClase}, la misma que usa la
 * inscripcion para otorgar el lugar. Si las dos calcularan distinto, el reporte diria "80 %" de
 * una clase que la inscripcion ya da por llena.
 *
 * <p>La oferta y el espacio se piden por {@code spi} —son tablas de {@code offering} y de
 * {@code resource}— y se recuerdan dentro de la consulta: un periodo de un anio repite las mismas
 * pocas ofertas cientos de veces, y preguntarlas una por clase seria un N+1 sobre otro modulo.
 *
 * <h2>Las canceladas no ocupan ni ofrecen</h2>
 *
 * <p>Se cuentan como canceladas y <b>no entran en la ocupacion</b>: su cupo se vacio al cancelar
 * (V60, {@code vaciarCupo}) y su capacidad no se ofrecio. Sumarlas abajo bajaria la ocupacion del
 * centro que mas cancela, que es justo el que necesita ver el numero real.
 *
 * <h2>La lista de espera no es ocupacion</h2>
 *
 * <p>Los lugares ocupados salen de {@code cupo_ocupado}, que <b>no</b> cuenta a quien espera
 * (RN-M28-005, y la validacion de RF-M23-007: "no contar lista de espera como ocupacion
 * confirmada"). La espera tiene su indicador propio en la seccion de participacion.
 *
 * <h2>El detalle es una fila por clase</h2>
 *
 * <p>Es lo que pide CA-M23-007-06 —"identificar porcentaje de ocupacion de Sala Pilates por
 * horario"—: fecha, hora, espacio y ocupacion de cada clase. Ninguna fila lleva personas.
 */
@Component
public class ClasesEnElReporte implements ReporteContributor {

	static final String SECCION = "clases";
	private static final String FUENTE = "M28 clase_programada";
	private static final String CRITERIO = "inicio de la clase, instante proyectado a la zona de "
			+ "la sede; incluye las canceladas";
	private static final String CRITERIO_OCUPACION = "inicio de la clase en el periodo; excluye "
			+ "las canceladas y la lista de espera";
	private static final String SIN_ESPACIO = "-";

	private final ActividadEnElReporteRepository consultas;
	private final CapacidadDeClase capacidad;
	private final OfertaDirectory ofertas;
	private final EspacioDirectory espacios;

	public ClasesEnElReporte(
			ActividadEnElReporteRepository consultas,
			CapacidadDeClase capacidad,
			OfertaDirectory ofertas,
			EspacioDirectory espacios) {

		this.consultas = consultas;
		this.capacidad = capacidad;
		this.ofertas = ofertas;
		this.espacios = espacios;
	}

	/**
	 * Tambien en {@code SERVICIOS} (AKINE-09.01b): CA-M23-009-06 pide ver la ocupacion de Pilates al
	 * lado de sus ventas de packs. La misma seccion y la misma formula, no una segunda.
	 */
	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.CLASES, ReporteCode.SERVICIOS);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Clases";
	}

	/** {@code clase:read}: el mismo con el que se lee la grilla de clases. */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.CLASE_READ;
	}

	/**
	 * Sabe recortarse a las clases que dicta el profesional que pide el reporte (G-1, DP-15): las
	 * que tienen su membership en {@code profesional_membership_id}.
	 */
	@Override
	public boolean filtraPorActividadPropia() {
		return true;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long organizationId = consulta.organizationId();
		long consultorioId = consulta.consultorioId();

		List<Object[]> clases = consultas.clasesDelPeriodo(
				organizationId, consultorioId,
				consulta.desdeInstante(), consulta.hastaInstante(), consulta.zona().getId(),
				consulta.recortadaAActividadPropia(), consulta.membershipsDelRecorte());

		// Los espacios en una sola consulta (docs/carga.md §6.4). La capacidad de un espacio no
		// tiene vigencia propia: el instante solo evalua si esta en servicio, que aca no importa.
		Set<Long> espacioIds = new HashSet<>();
		for (Object[] fila : clases) {
			if (fila[2] != null) {
				espacioIds.add(((Number) fila[2]).longValue());
			}
		}
		Map<Long, EspacioSnapshot> espaciosLeidos = espacioIds.isEmpty()
				? Map.of()
				: espacios.findTodos(organizationId, espacioIds, consulta.hastaInstante());
		Map<Long, Optional<OfertaSnapshot>> ofertasLeidas = new HashMap<>();

		long programadas = 0;
		long porDictar = 0;
		long enCurso = 0;
		long dictadas = 0;
		long canceladas = 0;
		long capacidadOfrecida = 0;
		long lugaresOcupados = 0;
		List<FilaDeReporte> filas = new ArrayList<>();

		for (Object[] fila : clases) {
			long ofertaId = ((Number) fila[1]).longValue();
			Long espacioId = fila[2] == null ? null : ((Number) fila[2]).longValue();
			int capacidadPropia = ((Number) fila[3]).intValue();
			int ocupados = ((Number) fila[4]).intValue();
			String estado = String.valueOf(fila[5]);
			Instant inicio = Instant.ofEpochSecond(((Number) fila[6]).longValue());

			programadas++;
			boolean cancelada = EstadoClase.CANCELADA.name().equals(estado);
			if (cancelada) {
				canceladas++;
			} else if (EstadoClase.REALIZADA.name().equals(estado)) {
				dictadas++;
			} else if (EstadoClase.EN_CURSO.name().equals(estado)) {
				enCurso++;
			} else if (EstadoClase.PROGRAMADA.name().equals(estado)) {
				porDictar++;
			}

			Optional<OfertaSnapshot> oferta = ofertasLeidas.computeIfAbsent(ofertaId,
					id -> ofertas.find(organizationId, consultorioId, id));
			Optional<EspacioSnapshot> espacio = espacioId == null
					? Optional.empty()
					: Optional.ofNullable(espaciosLeidos.get(espacioId));

			// Misma regla que CapacidadDeClase.efectiva(org, sede, clase): min(propia, oferta) y,
			// si hay espacio, tambien el espacio; sin oferta legible, la propia. El espacio ya
			// leido en lote se aplica aca para no volver a pedirlo clase por clase.
			int efectiva = oferta
					.map(o -> {
						int conOferta = capacidad.efectiva(organizationId, o, null, inicio, capacidadPropia);
						return espacio.map(e -> Math.min(conOferta, e.capacidad())).orElse(conOferta);
					})
					.orElse(capacidadPropia);

			if (!cancelada) {
				capacidadOfrecida += efectiva;
				lugaresOcupados += ocupados;
			}

			if (filas.size() < consulta.limiteFilas()) {
				String nombre = fila[10] != null
						? String.valueOf(fila[10])
						: oferta.map(OfertaSnapshot::nombreComercial).orElse("Clase");
				filas.add(FilaDeReporte.de(
						String.valueOf(fila[7]),
						String.format("%02d:%02d",
								((Number) fila[8]).intValue(), ((Number) fila[9]).intValue()),
						nombre,
						espacio.map(EspacioSnapshot::name).orElse(SIN_ESPACIO),
						estado,
						String.valueOf(efectiva),
						String.valueOf(ocupados),
						cancelada ? "" : Porcentajes.de(ocupados, efectiva).toPlainString()));
			}
		}

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.contando("clases-programadas", "Clases programadas",
						programadas, FUENTE, CRITERIO),
				IndicadorDeReporte.contando("clases-dictadas", "Dictadas (cerradas)",
						dictadas, FUENTE, CRITERIO + "; estado REALIZADA"),
				IndicadorDeReporte.contando("clases-en-curso", "En curso",
						enCurso, FUENTE, CRITERIO + "; estado EN_CURSO"),
				IndicadorDeReporte.contando("clases-por-dictar", "Por dictar",
						porDictar, FUENTE, CRITERIO + "; estado PROGRAMADA"),
				IndicadorDeReporte.contando("clases-canceladas", "Canceladas",
						canceladas, FUENTE, CRITERIO + "; estado CANCELADA"),
				IndicadorDeReporte.contando("clases-capacidad-ofrecida", "Lugares ofrecidos",
						capacidadOfrecida, FUENTE + " + capacidad de oferta (M27) y espacio (M04)",
						CRITERIO_OCUPACION + "; capacidad efectiva = min(clase, oferta, espacio)"),
				IndicadorDeReporte.contando("clases-lugares-ocupados", "Lugares ocupados",
						lugaresOcupados, FUENTE + " (cupo_ocupado)", CRITERIO_OCUPACION),
				IndicadorDeReporte.porcentaje("clases-ocupacion", "Ocupacion",
						Porcentajes.de(lugaresOcupados, capacidadOfrecida),
						FUENTE, "lugares ocupados sobre lugares ofrecidos; " + CRITERIO_OCUPACION));

		return new AporteDeReporte(
				SECCION, titulo(), indicadores,
				List.of("Fecha", "Hora", "Clase", "Espacio", "Estado",
						"Capacidad efectiva", "Lugares ocupados", "Ocupacion %"),
				filas, List.of());
	}
}
