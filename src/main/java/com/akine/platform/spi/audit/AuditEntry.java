package com.akine.platform.spi.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Un hecho auditable, tal como lo declara el modulo que lo produjo.
 *
 * <p>Es el unico tipo que cruza el borde hacia {@link AuditTrail}: los modulos consumidores
 * no conocen la entidad ni la tabla donde termina, solo este record.
 *
 * <p><b>Nunca lleva secretos ni contenido clinico.</b> Ni contrasenas, ni tokens, ni claves de
 * API, ni evoluciones, diagnosticos o notas de sesion. La auditoria registra QUE paso, QUIEN
 * lo hizo y SOBRE QUE, no el contenido del dato. La tabla se consulta para investigar
 * incidentes y termina en backups: meter ahi un dato sensible lo multiplica sin control.
 *
 * @param organizationId  tenant del hecho. {@code null} SOLO para eventos de plataforma sin
 *                        tenant; todo evento de negocio lo lleva
 * @param consultorioId   sede del hecho, o {@code null} si no pertenece a una sede concreta
 * @param actorAccountId  quien lo hizo. {@code null} = el sistema (job, migracion, onboarding
 *                        automatico). Es informacion, no un descuido: hay que poder
 *                        distinguir "lo hizo alguien" de "lo hizo el sistema"
 * @param eventType       que paso, en el catalogo de eventos del modulo emisor
 * @param entityType      sobre que tipo de entidad
 * @param entityId        id de la fila afectada, o {@code null} si el evento no apunta a una
 * @param previousState   estado anterior cuando el evento es una transicion
 * @param newState        estado nuevo cuando el evento es una transicion
 * @param details         contexto adicional. Nunca vacio de sentido y nunca sensible
 * @param reason          motivo declarado por el actor cuando la operacion lo exige
 * @param correlationId   correlaciona con el log estructurado del request (ADR-0005)
 * @param occurredAt      instante UTC del hecho
 */
public record AuditEntry(
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

	public AuditEntry {
		if (eventType == null || eventType.isBlank()) {
			throw new IllegalArgumentException("eventType es obligatorio en un evento de auditoria");
		}
		if (entityType == null || entityType.isBlank()) {
			throw new IllegalArgumentException("entityType es obligatorio en un evento de auditoria");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("occurredAt es obligatorio en un evento de auditoria");
		}
		// Copia inmutable: el emisor no puede alterar el detalle despues de haberlo declarado.
		details = details == null ? Map.of() : Map.copyOf(details);
	}
}
