package com.akine.platform.spi.audit;

import java.time.Instant;
import java.util.Set;

/**
 * Criterios del historial de agenda de una persona (V134, V139, {@link AuditQuery#deAgenda}).
 *
 * <p>Igual que {@link AuditEventFilter}, <b>nada de esto autoriza</b>: {@code organizationId} sale del
 * contexto validado, y {@code sedes} lo decide quien evaluo los permisos del lector.
 *
 * @param organizationId tenant, del contexto del request
 * @param memberships    todas las memberships de la persona en la organizacion, vigentes o no: con
 *                       memberships por sede, la misma persona tiene un id en cada una
 * @param eventTypes     tipos de evento a incluir por profesional; puede ser vacio si solo se piden
 *                       {@code eventosDeSede}
 * @param sedes          sedes que el lector alcanza, o {@code null} para la organizacion entera
 * @param desde          inclusivo, o {@code null}
 * @param hasta          exclusivo, o {@code null}
 * @param texto          "contiene" en el motivo y en el tipo de evento, o {@code null}
 * @param eventosDeSede  eventos SIN profesional (una excepcion de la sede entera) que el llamador ya
 *                       resolvio que tocan a esta persona; {@code null} = ninguno
 * @param traslados      como reconocer las tres filas de un cambio de sede para mostrar una sola;
 *                       {@code null} = no se unifican
 */
public record AuditAgendaFilter(
		long organizationId,
		Set<Long> memberships,
		Set<String> eventTypes,
		Set<Long> sedes,
		Instant desde,
		Instant hasta,
		String texto,
		EventosDeSede eventosDeSede,
		Traslados traslados) {

	public AuditAgendaFilter {
		if (memberships == null || memberships.isEmpty() || eventTypes == null) {
			throw new IllegalArgumentException("El historial de agenda exige memberships y tipos de evento");
		}
		if (eventTypes.isEmpty() && (eventosDeSede == null || eventosDeSede.vacio())) {
			throw new IllegalArgumentException("El historial de agenda exige al menos un tipo de evento");
		}
		memberships = Set.copyOf(memberships);
		eventTypes = Set.copyOf(eventTypes);
		sedes = sedes == null ? null : Set.copyOf(sedes);
	}

	public AuditAgendaFilter(long organizationId, Set<Long> memberships, Set<String> eventTypes, Set<Long> sedes,
			Instant desde, Instant hasta, String texto) {
		this(organizationId, memberships, eventTypes, sedes, desde, hasta, texto, null, null);
	}

	/**
	 * Eventos de una entidad que no lleva profesional y que igual se muestran: la decision de que tocan
	 * a la persona la tomo el llamador (que es dueño de la regla) y aca solo viajan los ids.
	 *
	 * @param entityType {@code entity_type} de la auditoria
	 * @param entityIds  ids de esas entidades
	 * @param eventTypes que eventos de esas entidades
	 */
	public record EventosDeSede(String entityType, Set<Long> entityIds, Set<String> eventTypes) {

		public EventosDeSede {
			entityIds = entityIds == null ? Set.of() : Set.copyOf(entityIds);
			eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
		}

		boolean vacio() {
			return entityIds.isEmpty() || eventTypes.isEmpty();
		}
	}

	/**
	 * Un cambio de sede audita tres hechos en la misma transaccion: la cancelacion y el traslado sobre el
	 * turno original y la reserva del nuevo, que lleva {@code turnoOrigenId} (V139). Se muestra solo
	 * {@code visible}; los otros dos se absorben. El traslado aparece tambien para el profesional y la
	 * sede de DESTINO, que estan en la reserva absorbida.
	 *
	 * @param visible    {@code TURNO_TRASLADADO}
	 * @param cancelacion {@code TURNO_CANCELADO} del mismo turno que el visible
	 * @param reserva    {@code TURNO_RESERVADO} del turno cuyo origen es el del visible
	 */
	public record Traslados(String visible, String cancelacion, String reserva) {
	}
}
