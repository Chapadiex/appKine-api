package com.akine.platform.spi.audit;

import java.time.Instant;
import java.util.Set;

/**
 * Criterios del historial de agenda de una persona (V134, {@link AuditQuery#deAgenda}).
 *
 * <p>Igual que {@link AuditEventFilter}, <b>nada de esto autoriza</b>: {@code organizationId} sale del
 * contexto validado, y {@code sedes} lo decide quien evaluo los permisos del lector.
 *
 * @param organizationId tenant, del contexto del request
 * @param memberships    todas las memberships de la persona en la organizacion, vigentes o no: con
 *                       memberships por sede, la misma persona tiene un id en cada una
 * @param eventTypes     tipos de evento a incluir; nunca vacio
 * @param sedes          sedes que el lector alcanza, o {@code null} para la organizacion entera
 * @param desde          inclusivo, o {@code null}
 * @param hasta          exclusivo, o {@code null}
 * @param texto          "contiene" en el motivo y en el tipo de evento, o {@code null}
 */
public record AuditAgendaFilter(
		long organizationId,
		Set<Long> memberships,
		Set<String> eventTypes,
		Set<Long> sedes,
		Instant desde,
		Instant hasta,
		String texto) {

	public AuditAgendaFilter {
		if (memberships == null || memberships.isEmpty() || eventTypes == null || eventTypes.isEmpty()) {
			throw new IllegalArgumentException("El historial de agenda exige memberships y tipos de evento");
		}
		memberships = Set.copyOf(memberships);
		eventTypes = Set.copyOf(eventTypes);
		sedes = sedes == null ? null : Set.copyOf(sedes);
	}
}
