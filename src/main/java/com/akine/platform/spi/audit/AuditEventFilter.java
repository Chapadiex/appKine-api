package com.akine.platform.spi.audit;

import java.time.Instant;

/**
 * Criterios de una consulta de auditoria (RF-M24-002, RF-M24-003, RF-M24-004).
 *
 * <p><b>{@code organizationId} nunca sale de un parametro del cliente.</b> Lo pone quien
 * autoriza, tomandolo del contexto ya revalidado del request. Si viniera de la URL, cambiarlo
 * seria leer la auditoria de otro tenant.
 *
 * <p>{@code consultorioId} materializa el alcance de {@code auditoria:read}: un
 * {@code ORG_ADMIN} consulta con {@code null} y ve la organizacion entera; un
 * {@code CONSULTORIO_ADMIN} consulta con su sede.
 *
 * <p>Los tres modos de filtro son excluyentes por diseño, uno por RF, y cada uno tiene su
 * indice: entidad ({@code ix_audit_event_entity}), actor ({@code ix_audit_event_actor}) y
 * periodo ({@code ix_audit_event_org_time} / {@code ix_audit_event_org_loc_time}, agregados por
 * la migracion V14 justamente porque los dos de V5 no servian para un rango sobre
 * {@code occurred_at}).
 *
 * @param organizationId  tenant, del contexto del request. Obligatorio
 * @param consultorioId   sede a la que se acota la lectura, o {@code null} para toda la
 *                        organizacion
 * @param entityType      tipo de entidad, junto con {@code entityId} (RF-M24-002)
 * @param entityId        id de la entidad (RF-M24-002)
 * @param actorAccountId  actor cuya actividad se investiga (RF-M24-003)
 * @param desde           inicio del periodo (RF-M24-004)
 * @param hasta           fin del periodo (RF-M24-004)
 */
public record AuditEventFilter(
		long organizationId,
		Long consultorioId,
		String entityType,
		Long entityId,
		Long actorAccountId,
		Instant desde,
		Instant hasta) {
}
