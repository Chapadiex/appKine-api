package com.akine.platform.spi.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Una fila de auditoria tal como la ven los otros modulos.
 *
 * <p><b>Es un record del {@code spi}, no la entity.</b> {@code AuditEvent} vive en
 * {@code platform.domain} y devolverla obligaria a los consumidores a compilar contra el
 * dominio de otro modulo, que es exactamente lo que ArchUnit rechaza. Es la contraparte de
 * lectura de {@link AuditEntry}.
 *
 * <p>Rige la misma regla que en la escritura: aca no hay ni puede haber secretos ni contenido
 * clinico, porque {@code AuditTrailImpl} ya los redacta al escribir.
 */
public record AuditEventSummary(
		long id,
		Long organizationId,
		Long consultorioId,
		Long actorAccountId,
		String eventType,
		String entityType,
		Long entityId,
		String previousState,
		String newState,
		Map<String, String> details,
		String reason,
		String correlationId,
		Instant occurredAt) {

	public AuditEventSummary {
		details = details == null ? Map.of() : Map.copyOf(details);
	}
}
