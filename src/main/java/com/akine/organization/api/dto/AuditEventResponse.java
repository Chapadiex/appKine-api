package com.akine.organization.api.dto;

import com.akine.platform.spi.audit.AuditEventSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Una fila del registro de auditoria del tenant (RF-M24-002/003/004).
 *
 * <p>Se construye desde {@code AuditEventSummary}, que ya es un record del {@code spi} de
 * {@code platform}: la entity {@code AuditEvent} no sale de su modulo.
 *
 * <p><b>Aca no hay ni puede haber contenido clinico ni secretos.</b> No es una promesa de esta
 * capa: el escritor de auditoria los redacta antes de persistirlos, asi que lo que este DTO
 * publica es lo que ya quedo guardado sin ellos.
 */
@Schema(description = "Hecho registrado en la auditoria de la organizacion")
public record AuditEventResponse(

		@Schema(description = "Identificador del hecho", example = "1024")
		long id,

		@Schema(description = "Organizacion sobre la que ocurrio, o null si es de plataforma",
				example = "7")
		Long organizationId,

		@Schema(description = "Sede sobre la que ocurrio, si aplica", example = "3")
		Long consultorioId,

		@Schema(description = "Cuenta que ejecuto la operacion", example = "18")
		Long actorAccountId,

		@Schema(description = "Tipo de hecho", example = "MEMBERSHIP_ROLE_CHANGED")
		String eventType,

		@Schema(description = "Tipo de entidad afectada", example = "MEMBERSHIP")
		String entityType,

		@Schema(description = "Identificador de la entidad afectada", example = "42")
		Long entityId,

		@Schema(description = "Estado anterior, si el hecho fue una transicion", example = "PROFESIONAL")
		String previousState,

		@Schema(description = "Estado nuevo, si el hecho fue una transicion", example = "CONSULTORIO_ADMIN")
		String newState,

		@Schema(description = "Datos adicionales del hecho, siempre como texto plano")
		Map<String, String> details,

		@Schema(description = "Motivo declarado por quien ejecuto la operacion")
		String reason,

		@Schema(description = "Identificador que agrupa los hechos de un mismo request",
				example = "9f3c1d2a-4b5e-4a6f-8c7d-0e1f2a3b4c5d")
		String correlationId,

		@Schema(description = "Momento en que ocurrio")
		Instant occurredAt) {

	public static AuditEventResponse from(AuditEventSummary summary) {
		return new AuditEventResponse(
				summary.id(),
				summary.organizationId(),
				summary.consultorioId(),
				summary.actorAccountId(),
				summary.eventType(),
				summary.entityType(),
				summary.entityId(),
				summary.previousState(),
				summary.newState(),
				summary.details(),
				summary.reason(),
				summary.correlationId(),
				summary.occurredAt());
	}
}
