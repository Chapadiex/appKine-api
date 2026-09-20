package com.akine.encounter.infrastructure;

import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lo que {@code encounter} aporta a los reportes: atenciones realizadas (RF-M23-003).
 *
 * <h2>Sesion es atencion, no reserva</h2>
 *
 * <p>Esta seccion cuenta lo que <b>ocurrio</b>. Los turnos los cuenta {@code scheduling} en la
 * suya, y las dos no se funden porque DP-05 lo prohibe. La diferencia entre los dos numeros no es
 * un error del reporte: <b>es informacion</b> —turnos que no derivaron en atencion—, y fusionarlos
 * la borraria.
 *
 * <h2>RN-M23-004, hecho numero</h2>
 *
 * <p><i>"Las sesiones de diferentes casos no se mezclan en numeracion ni en conteos
 * contextuales."</i> Se cumple de dos formas: las sesiones con caso y sin caso se cuentan en
 * indicadores <b>separados</b> —en vez de caer las dos en el mismo balde— y el detalle agrupa por
 * {@code caso_id}, nunca por paciente.
 *
 * <h2>Minimizacion clinica</h2>
 *
 * <p>Conteos y, a lo sumo, un {@code caso_id}. <b>Ni una evolucion, ni un diagnostico, ni una nota
 * de cierre, ni un nombre.</b> Es la misma linea que trazo el Paciente 360 en 03.02, y aca tiene un
 * motivo extra: un {@code ADMINISTRATIVO} que tenga el permiso de esta seccion no puede terminar
 * deduciendo que le paso a quien.
 *
 * <p>{@link #esClinica()} devuelve {@code true}: consultar un reporte que incluya esta seccion
 * <b>queda auditado</b>, aunque el aporte sea agregado. AKINE-04.01 dejo fijado que toda lectura
 * clinica se audita, y lo que la regla protege es el acceso, no el volumen.
 */
@Component
public class SesionesEnElReporte implements ReporteContributor {

	private static final String SECCION = "sesiones";
	private static final String FUENTE = "M14 sesion";
	private static final String CRITERIO_CIERRE =
			"cerrada_en, instante proyectado a la zona de la sede; excluye las dadas de baja";

	private final SesionRepository sesiones;

	public SesionesEnElReporte(SesionRepository sesiones) {
		this.sesiones = sesiones;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.CLINICO, ReporteCode.OPERATIVO);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Sesiones";
	}

	/** {@code sesion:register}: el mismo con el que se registra y se lee una sesion. */
	@Override
	public String permisoRequerido() {
		return "sesion:register";
	}

	@Override
	public boolean esClinica() {
		return true;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long cerradas = sesiones.contarCerradasEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante());
		long conCaso = sesiones.contarCerradasConCasoEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante());
		long presentes = sesiones.contarCerradasPorAsistenciaEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(), "PRESENTE");
		long ausentes = sesiones.contarCerradasPorAsistenciaEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(), "AUSENTE");
		long enBorrador = sesiones.contarEnBorradorEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante());

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.contando(
						"sesiones-cerradas", "Sesiones cerradas", cerradas, FUENTE, CRITERIO_CIERRE),
				IndicadorDeReporte.contando("sesiones-con-caso", "Con caso clinico", conCaso,
						FUENTE, CRITERIO_CIERRE),
				IndicadorDeReporte.contando("sesiones-sin-caso", "Sin caso clinico",
						cerradas - conCaso, FUENTE, CRITERIO_CIERRE),
				IndicadorDeReporte.contando(
						"asistencia-presente", "Presentes", presentes, FUENTE, CRITERIO_CIERRE),
				IndicadorDeReporte.contando(
						"asistencia-ausente", "Ausentes", ausentes, FUENTE, CRITERIO_CIERRE),
				// Las abiertas se cuentan por `iniciada_en`: una sesion en borrador todavia no
				// tiene cierre, y contarla por una columna nula la dejaria siempre en cero.
				IndicadorDeReporte.contando("sesiones-en-borrador", "Abiertas sin cerrar",
						enBorrador, FUENTE,
						"iniciada_en, instante proyectado a la zona de la sede; estado BORRADOR"));

		List<FilaDeReporte> filas = new ArrayList<>();
		for (Object[] fila : sesiones.contarCerradasPorCasoEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante(), consulta.limiteFilas())) {

			filas.add(FilaDeReporte.de(
					fila[0] == null ? "sin caso" : String.valueOf(fila[0]),
					String.valueOf(((Number) fila[1]).longValue())));
		}

		return new AporteDeReporte(
				SECCION, titulo(), indicadores,
				List.of("Caso", "Sesiones cerradas"), filas, List.of());
	}
}
