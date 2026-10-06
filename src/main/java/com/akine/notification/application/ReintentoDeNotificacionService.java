package com.akine.notification.application;

import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.PermissionCodes;
import com.akine.notification.domain.exception.NotificacionNoEncontradaException;
import com.akine.notification.domain.exception.NotificacionNoReintentableException;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Reintento administrativo de una notificacion del tenant (RF-M26-005, paquete A-3).
 *
 * <p>Es el llamador que {@link OutboxDispatchService#reintentarManualmente} esperaba desde 01.02.
 * Lo unico que agrega es lo que un endpoint necesita y el worker no: contexto, permiso, tenant y
 * un error que distinga "no existe" de "no se puede reintentar".
 *
 * <p><b>Solo notificaciones con organizacion.</b> Las de identidad —activacion, recuperacion—
 * nacen sin tenant ({@code organization_id} NULL) y quedan para un administrador de plataforma,
 * que hoy no tiene endpoint. Para un usuario del tenant son 404, igual que las de otra
 * organizacion.
 */
@Service
public class ReintentoDeNotificacionService {

	static final String NOTIFICACION_REINTENTADA = "NOTIFICACION_REINTENTADA";
	static final String ENTITY_NOTIFICACION = "Notificacion";

	private final NotificationOutboxRepositoryPort repository;
	private final OutboxDispatchService dispatchService;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final NotificationClock clock;

	public ReintentoDeNotificacionService(
			NotificationOutboxRepositoryPort repository,
			OutboxDispatchService dispatchService,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			NotificationClock clock) {
		this.repository = repository;
		this.dispatchService = dispatchService;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.clock = clock;
	}

	/**
	 * Devuelve a la cola una notificacion FALLIDA o AGOTADA.
	 *
	 * <p>Orden de las precondiciones: contexto (403), organizacion de la ruta (404), permiso
	 * (403), existencia en el tenant (404), estado (409). El permiso va antes de buscar la fila
	 * para que alguien sin permiso no pueda sondear que ids existen.
	 */
	@Transactional
	public void reintentar(OperatingActor actor, long orgId, long notificacionId) {
		long organizationId = exigirContexto(actor);
		if (organizationId != orgId) {
			// La organizacion de la ruta no es la del contexto: 404, nunca 403.
			throw new NotificacionNoEncontradaException(notificacionId);
		}
		Instant ahora = clock.now();
		permissionGuard.requirePermission(PermissionQuery.of(
				actor.accountId(), PermissionCodes.NOTIFICACION_RETRY, organizationId, ahora));

		NotificationOutboxEntry entry = repository.findById(notificacionId)
				.filter(e -> Objects.equals(e.getOrganizationId(), organizationId))
				.orElseThrow(() -> new NotificacionNoEncontradaException(notificacionId));
		String estadoPrevio = entry.getEstado().name();
		if (!entry.getEstado().admiteReintentoManual()) {
			throw new NotificacionNoReintentableException(notificacionId, entry.getEstado());
		}
		if (!dispatchService.reintentarManualmente(notificacionId)) {
			// El worker la tomo entre la lectura y el reintento. No hay nada que reintentar.
			throw new NotificacionNoReintentableException(notificacionId, entry.getEstado());
		}

		auditTrail.record(new AuditEntry(
				organizationId, null, actor.accountId(),
				NOTIFICACION_REINTENTADA, ENTITY_NOTIFICACION, notificacionId,
				estadoPrevio, OutboxStatus.REINTENTABLE.name(),
				Map.of("tipo", entry.getTipo().name()),
				null, MDC.get("traceId"), ahora));
	}

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("Reintentar una notificacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}
}
