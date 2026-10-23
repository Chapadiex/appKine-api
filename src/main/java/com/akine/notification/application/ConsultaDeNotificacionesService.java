package com.akine.notification.application;

import com.akine.notification.domain.ErrorSanitizer;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.PermissionCodes;
import com.akine.notification.domain.exception.NotificacionesNoAccesiblesException;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.listado.OrdenDeListado;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Listado administrativo de las notificaciones del tenant (huecos chicos, complemento de A-3).
 *
 * <p>El reintento existia y no habia forma de saber QUE reintentar: el frontend pedia el id a
 * mano. Esto es lo que lo encuentra.
 *
 * <p><b>Sin PHI.</b> El destinatario sale enmascarado ({@code j***@dominio}), el payload no sale
 * nunca y el ultimo error es el ya sanitizado al persistirlo, recortado a un resumen. Lo que se
 * ve es lo minimo para decidir "esto rebota, reintento" o "este dominio no existe, no tiene
 * sentido".
 *
 * <p>Mismas precondiciones y en el mismo orden que el reintento: contexto (403), organizacion de
 * la ruta (404), permiso (403).
 */
@Service
public class ConsultaDeNotificacionesService {

	/** Lo que se lista si no se pide estado: lo que el reintento admite. */
	static final Set<OutboxStatus> ESTADOS_POR_DEFECTO =
			EnumSet.of(OutboxStatus.FALLIDA, OutboxStatus.AGOTADA);

	/** Largo del resumen del ultimo error. El completo sigue en la base, ya sanitizado. */
	static final int LARGO_RESUMEN_ERROR = 200;

	private final NotificationOutboxRepositoryPort repository;
	private final PermissionGuard permissionGuard;
	private final NotificationClock clock;

	public ConsultaDeNotificacionesService(
			NotificationOutboxRepositoryPort repository,
			PermissionGuard permissionGuard,
			NotificationClock clock) {
		this.repository = repository;
		this.permissionGuard = permissionGuard;
		this.clock = clock;
	}

	/** Sin texto ni orden: la forma de antes de DP-45. */
	@Transactional(readOnly = true)
	public Page<NotificacionResumen> listar(
			OperatingActor actor, long orgId, Collection<OutboxStatus> estados, int page, int size) {
		return listar(actor, orgId, estados, null, Optional.empty(), page, size);
	}

	/** DP-45: campo del contrato → propiedad de la notificacion. */
	public static final Map<String, String> ORDEN_DE_NOTIFICACIONES = Map.of(
			"creadaEn", "createdAt",
			"estado", "estado",
			"tipo", "tipo",
			"intentos", "intentos");

	/**
	 * Notificaciones de la organizacion en los estados pedidos, de la mas nueva a la mas vieja.
	 *
	 * <p>DP-45: {@code q} coincide con el TIPO que contiene el texto (sin mayusculas ni acentos)
	 * o con el destinatario EXACTO. Exacto a proposito: el listado lo devuelve enmascarado, y un
	 * "contiene" sobre la casilla real dejaria reconstruirla letra por letra preguntando.
	 *
	 * @param estados estados a incluir; vacio o {@code null} equivale a FALLIDA y AGOTADA
	 */
	@Transactional(readOnly = true)
	public Page<NotificacionResumen> listar(
			OperatingActor actor, long orgId, Collection<OutboxStatus> estados, String q,
			Optional<OrdenDeListado> orden, int page, int size) {

		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException(
					"Consultar notificaciones requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		if (organizationId != orgId) {
			// La organizacion de la ruta no es la del contexto: 404, nunca 403.
			throw new NotificacionesNoAccesiblesException();
		}
		Instant ahora = clock.now();
		permissionGuard.requirePermission(PermissionQuery.of(
				actor.accountId(), PermissionCodes.NOTIFICACION_READ, organizationId, ahora));

		Set<OutboxStatus> filtro = estados == null || estados.isEmpty()
				? ESTADOS_POR_DEFECTO
				: EnumSet.copyOf(estados);
		Sort sort = orden
				.map(pedido -> pedido.aSort(ORDEN_DE_NOTIFICACIONES.get(pedido.campo()), "id"))
				.orElse(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		PageRequest pagina = PageRequest.of(page, size, sort);

		if (!TextoDeBusqueda.hay(q)) {
			return repository.findByOrganizationIdAndEstadoIn(organizationId, filtro, pagina)
					.map(ConsultaDeNotificacionesService::resumir);
		}
		List<NotificationType> tipos = Arrays.stream(NotificationType.values())
				.filter(tipo -> TextoDeBusqueda.contiene(tipo.name().replace('_', ' '), q)
						|| TextoDeBusqueda.contiene(tipo.name(), q))
				.toList();
		return repository.buscarPorTexto(organizationId, filtro, !tipos.isEmpty(),
						tipos.isEmpty() ? List.of(NotificationType.values()) : tipos,
						q.strip(), pagina)
				.map(ConsultaDeNotificacionesService::resumir);
	}

	static NotificacionResumen resumir(NotificationOutboxEntry entry) {
		return new NotificacionResumen(
				entry.getId(),
				entry.getTipo(),
				ErrorSanitizer.maskEmail(entry.getDestinatario()),
				entry.getEstado(),
				entry.getEstado().admiteReintentoManual(),
				entry.getIntentos(),
				entry.getMaxIntentos(),
				resumirError(entry.getErrorSanitizado()),
				entry.getCreatedAt(),
				entry.getUpdatedAt(),
				entry.getProximaEjecucionEn(),
				entry.getEnviadoEn());
	}

	/**
	 * El error ya sanitizado, recortado a un resumen. Se vuelve a pasar por el sanitizador por si
	 * la fila es anterior a alguna regla nueva: es barato y no hay forma de que empeore.
	 */
	static String resumirError(String errorSanitizado) {
		if (errorSanitizado == null || errorSanitizado.isBlank()) {
			return null;
		}
		String limpio = ErrorSanitizer.sanitize(errorSanitizado);
		return limpio.length() <= LARGO_RESUMEN_ERROR
				? limpio
				: limpio.substring(0, LARGO_RESUMEN_ERROR - 3) + "...";
	}

	/** Lo que se muestra de una notificacion. Nada de payload, destinatario enmascarado. */
	public record NotificacionResumen(
			long id,
			NotificationType tipo,
			String destinatarioEnmascarado,
			OutboxStatus estado,
			boolean reintentable,
			int intentos,
			int maxIntentos,
			String ultimoError,
			Instant creadaEn,
			Instant actualizadaEn,
			Instant proximaEjecucionEn,
			Instant enviadaEn) {
	}
}
