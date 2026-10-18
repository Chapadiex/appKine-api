package com.akine.activity.infrastructure;

import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lo que {@code activity} aporta al reporte de clases sobre la gente: inscriptos, lista de
 * espera, asistentes y ausentes (RF-M23-007, con RF-M28-002 a RF-M28-004 y RF-M28-007).
 *
 * <h2>Inscripcion y asistencia son dos numeros, y ninguno es una sesion</h2>
 *
 * <p>La inscripcion es la <b>reserva del lugar</b> y la asistencia el <b>hecho</b> de haber
 * estado (V61). Se cuentan separadas porque responden preguntas distintas —cuanta gente se anoto,
 * cuanta vino— y porque la asistencia puede corregirse sin que la inscripcion cambie. Ninguna de
 * las dos es una sesion clinica (RN-M28-007, RN-M23-005): la atencion derivada desde una clase la
 * cuenta {@code encounter} en su seccion, y esta no la suma.
 *
 * <h2>La lista de espera se cuenta aparte, nunca como ocupacion</h2>
 *
 * <p>Validacion de RF-M23-007: "no contar lista de espera como ocupacion confirmada salvo
 * indicador especifico". Este es el indicador especifico: {@code participacion-en-espera} son las
 * inscripciones que <b>siguen</b> esperando, y {@code participacion-promovidas} las que salieron de
 * la espera hacia un lugar (RF-M28-004). Los inscriptos que ocupan lugar son los cuatro estados que
 * consumen cupo.
 *
 * <h2>Permiso</h2>
 *
 * <p>{@code inscripcion:read}: es el mismo con el que se lee la lista de una clase y la asistencia
 * de cada participante. Sin el, la seccion se omite y se declara.
 */
@Component
public class ParticipacionEnElReporte implements ReporteContributor {

	static final String SECCION = "participacion";
	private static final String FUENTE_INSCRIPCION = "M28 inscripcion_clase";
	private static final String FUENTE_ASISTENCIA = "M28 asistencia_actividad";
	private static final String CRITERIO = "inicio de la clase, instante proyectado a la zona de la "
			+ "sede; incluye clases e inscripciones canceladas";

	private final ActividadEnElReporteRepository consultas;

	public ParticipacionEnElReporte(ActividadEnElReporteRepository consultas) {
		this.consultas = consultas;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.CLASES);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Inscripciones y asistencia";
	}

	@Override
	public String permisoRequerido() {
		return PermissionCodes.INSCRIPCION_READ;
	}

	/** Se recorta por el profesional de la clase, igual que la seccion de clases. */
	@Override
	public boolean filtraPorActividadPropia() {
		return true;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		Map<String, Long> inscripciones = new HashMap<>();
		long totalInscripciones = 0;
		long promovidas = 0;
		for (Object[] fila : consultas.inscripcionesPorEstado(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(),
				consulta.recortadaAActividadPropia(), consulta.membershipsDelRecorte())) {

			long cantidad = ((Number) fila[1]).longValue();
			inscripciones.merge(String.valueOf(fila[0]), cantidad, Long::sum);
			totalInscripciones += cantidad;
			promovidas += fila[2] == null ? 0 : ((Number) fila[2]).longValue();
		}

		Map<String, Long> asistencias = new HashMap<>();
		long registradas = 0;
		long corregidas = 0;
		for (Object[] fila : consultas.asistenciasPorResultado(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(),
				consulta.recortadaAActividadPropia(), consulta.membershipsDelRecorte())) {

			long cantidad = ((Number) fila[1]).longValue();
			asistencias.merge(String.valueOf(fila[0]), cantidad, Long::sum);
			registradas += cantidad;
			corregidas += fila[2] == null ? 0 : ((Number) fila[2]).longValue();
		}

		long ocupanLugar = 0;
		for (EstadoInscripcion estado : EstadoInscripcion.values()) {
			if (estado.consumeCupo()) {
				ocupanLugar += contar(inscripciones, estado.name());
			}
		}
		long presentes = contar(asistencias, ResultadoAsistencia.PRESENTE.name());
		long tarde = contar(asistencias, ResultadoAsistencia.PRESENTE_TARDE.name());
		long ausentes = contar(asistencias, ResultadoAsistencia.AUSENTE.name());

		String criterioAsistencia = CRITERIO + "; resultado vigente, ya corregido";

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.contando("participacion-inscripciones", "Inscripciones",
						totalInscripciones, FUENTE_INSCRIPCION, CRITERIO),
				IndicadorDeReporte.contando("participacion-inscriptos", "Inscriptos con lugar",
						ocupanLugar, FUENTE_INSCRIPCION,
						CRITERIO + "; RESERVADA, CONFIRMADA, ASISTIO y AUSENTE"),
				IndicadorDeReporte.contando("participacion-confirmadas", "Confirmadas",
						contar(inscripciones, EstadoInscripcion.CONFIRMADA.name()),
						FUENTE_INSCRIPCION, CRITERIO + "; estado CONFIRMADA"),
				IndicadorDeReporte.contando("participacion-canceladas", "Inscripciones canceladas",
						contar(inscripciones, EstadoInscripcion.CANCELADA.name()),
						FUENTE_INSCRIPCION, CRITERIO + "; estado CANCELADA"),
				IndicadorDeReporte.contando("participacion-en-espera", "En lista de espera",
						contar(inscripciones, EstadoInscripcion.LISTA_ESPERA.name()),
						FUENTE_INSCRIPCION,
						CRITERIO + "; estado LISTA_ESPERA; no ocupa lugar (RN-M28-005)"),
				IndicadorDeReporte.contando("participacion-promovidas", "Promovidas desde la espera",
						promovidas, FUENTE_INSCRIPCION, CRITERIO + "; promovida_en no nulo"),
				IndicadorDeReporte.contando("participacion-asistencias", "Asistencias registradas",
						registradas, FUENTE_ASISTENCIA, criterioAsistencia),
				IndicadorDeReporte.contando("participacion-presentes", "Presentes",
						presentes, FUENTE_ASISTENCIA, criterioAsistencia + "; PRESENTE"),
				IndicadorDeReporte.contando("participacion-presentes-tarde", "Presentes con demora",
						tarde, FUENTE_ASISTENCIA, criterioAsistencia + "; PRESENTE_TARDE"),
				IndicadorDeReporte.contando("participacion-ausentes", "Ausentes",
						ausentes, FUENTE_ASISTENCIA, criterioAsistencia + "; AUSENTE"),
				IndicadorDeReporte.contando("participacion-asistencias-corregidas",
						"Asistencias corregidas", corregidas, FUENTE_ASISTENCIA,
						criterioAsistencia + "; corregidas al menos una vez"),
				IndicadorDeReporte.porcentaje("participacion-tasa-asistencia", "Asistencia",
						Porcentajes.de(presentes + tarde, registradas), FUENTE_ASISTENCIA,
						"presentes (a tiempo o con demora) sobre asistencias registradas; "
								+ criterioAsistencia));

		return AporteDeReporte.de(SECCION, titulo(), indicadores);
	}

	private static long contar(Map<String, Long> porClave, String clave) {
		return porClave.getOrDefault(clave, 0L);
	}
}
