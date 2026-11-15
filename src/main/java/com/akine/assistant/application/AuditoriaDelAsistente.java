package com.akine.assistant.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Escribe {@code ASISTENTE_CONSULTA} en su propia transaccion.
 *
 * <p>Va aparte y no en una transaccion que envuelva toda la consulta a proposito: las herramientas
 * llaman a servicios {@code @Transactional} de otros modulos, y uno que rechaza al actor con
 * {@code AccessDeniedException} marcaria la transaccion compartida como rollback-only. Atrapar la
 * excepcion no la desmarca y Spring lanzaria {@code UnexpectedRollbackException} al commitear (regla
 * transversal del proyecto). Asi, cada lectura vive en la transaccion de su servicio y la auditoria
 * en la suya.
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

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void registrar(long organizationId, long consultorioId, long accountId, Map<String, String> detalles) {
		auditTrail.record(new AuditEntry(organizationId, consultorioId, accountId, EVENTO, ENTIDAD, null,
				null, null, detalles, null, MDC.get("traceId"), Instant.now()));
	}
}
