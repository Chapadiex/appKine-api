package com.akine.assistant.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Escribe {@code ASISTENTE_CONSULTA} en su propia transaccion.
 *
 * <p>La consulta no tiene una transaccion que la envuelva a proposito: las herramientas llaman a
 * servicios {@code @Transactional} de otros modulos, y uno que rechaza al actor con
 * {@code AccessDeniedException} marcaria una transaccion compartida como rollback-only (atraparla no
 * la desmarca y Spring lanzaria {@code UnexpectedRollbackException}). Cada lectura vive en la de su
 * servicio y la auditoria en la suya. Es {@code REQUIRED} y no {@code REQUIRES_NEW}: si alguna vez se
 * envuelve la consulta, la auditoria se suma a esa transaccion en vez de pedir una segunda conexion
 * (el traba del pool de docs/carga.md §8).
 *
 * <p><b>Sin la pregunta textual</b>: puede traer nombres y lo que se quiere saber de ellos. Se
 * registran la intencion, los parametros resueltos y el resultado.
 */
@Service
public class AuditoriaDelAsistente {

	static final String EVENTO = "ASISTENTE_CONSULTA";
	static final String ENTIDAD = "ASISTENTE";

	private final AuditTrail auditTrail;

	public AuditoriaDelAsistente(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	/**
	 * Una consulta libre que leyo vistas {@code clinico_*}: evento propio, para que la auditoria
	 * clinica la encuentre aparte. Sin la pregunta ni las filas: solo que vistas y cuantas filas.
	 */
	@Transactional
	public void registrarLecturaClinica(long organizationId, long consultorioId, long accountId,
			java.util.Set<String> vistas, int filas) {
		auditTrail.record(new AuditEntry(organizationId, consultorioId, accountId, EVENTO_CLINICO, ENTIDAD, null,
				null, null, Map.of("vistas", String.join(",", vistas), "filas", String.valueOf(filas),
						"justificacion", "Consulta al asistente de datos"),
				null, MDC.get("traceId"), Instant.now()));
	}

	static final String EVENTO_CLINICO = "ASISTENTE_LECTURA_CLINICA";

	/**
	 * Una accion propuesta, confirmada, rechazada o descartada. Con el tipo y los ids; sin la frase ni
	 * el contenido de la tarjeta. Lo que la accion graba lo audita ademas su propio servicio.
	 */
	@Transactional
	public void registrarAccion(String evento, long organizationId, long consultorioId, long accountId,
			Map<String, String> detalles) {
		auditTrail.record(new AuditEntry(organizationId, consultorioId, accountId, evento, ENTIDAD, null,
				null, null, detalles, null, MDC.get("traceId"), Instant.now()));
	}

	@Transactional
	public void registrar(long organizationId, long consultorioId, long accountId, Map<String, String> detalles) {
		auditTrail.record(new AuditEntry(organizationId, consultorioId, accountId, EVENTO, ENTIDAD, null,
				null, null, detalles, null, MDC.get("traceId"), Instant.now()));
	}
}
