package com.akine.resource.application;

import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CatalogoSolicitud;
import com.akine.resource.domain.CatalogoTipo;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.SolicitudEstado;
import com.akine.resource.domain.exception.SolicitudDuplicadaException;
import com.akine.resource.domain.exception.SolicitudNotAccessibleException;
import com.akine.resource.domain.exception.SolicitudYaResueltaException;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solicitudes de alta de concepto GLOBAL (RF-M06-005).
 *
 * <h2>Que problema resuelve, y que problema NO</h2>
 *
 * <p>Un centro necesita una especialidad que el catalogo de plataforma no tiene. No puede
 * crearla el como global —seria decidir por todos los tenants del SaaS— asi que la pide.
 * <b>Mientras tanto no queda bloqueado:</b> puede crear el concepto como contextual suyo y
 * seguir trabajando. La solicitud existe para que el catalogo comun crezca con criterio, no para
 * frenar a nadie, y esta clase no impide ni condiciona ninguna operacion del catalogo.
 *
 * <h2>Quien hace que</h2>
 *
 * <ul>
 *   <li><b>Crear</b>: un administrador del tenant, con contexto elegido. Interinamente exige
 *       {@code consultorio:manage}, por el mismo motivo que las mutaciones contextuales del
 *       catalogo — ver {@code CatalogoService}.</li>
 *   <li><b>Listar</b>: el tenant ve <b>las suyas</b>; el administrador de plataforma ve la
 *       bandeja completa. Es la unica consulta cross-tenant del modulo, y por eso el rol se
 *       verifica antes de elegir la consulta y no despues de filtrar el resultado.</li>
 *   <li><b>Resolver</b>: solamente la plataforma.</li>
 * </ul>
 *
 * <h2>Idempotencia</h2>
 *
 * <p>CA-M06-005-05 exige que un reintento no duplique efectos. Lo sostiene la BASE —el unique
 * sobre {@code (organization_id, tipo, nombre_pendiente)}— y no una comprobacion previa: entre
 * un {@code SELECT} y un {@code INSERT} caben dos requests, y el unique no. La aplicacion
 * traduce el choque a 409.
 */
@Service
public class CatalogoSolicitudService {

	private static final Logger log = LoggerFactory.getLogger(CatalogoSolicitudService.class);

	/** Centinela de "sin filtro de estado". Ver los puertos del modulo. */
	private static final String SIN_FILTRO = "";

	private final CatalogoRepositoryPorts.SolicitudPort solicitudes;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public CatalogoSolicitudService(
			CatalogoRepositoryPorts.SolicitudPort solicitudes,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.solicitudes = solicitudes;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	/** Alta de una solicitud (RF-M06-005). */
	@Transactional
	public CatalogoSolicitudView crear(
			OperatingActor actor,
			CatalogoTipo tipo,
			String nombrePropuesto,
			String codigoPropuesto,
			String justificacion) {

		long organizationId = exigirGestionDelTenant(actor);
		Instant ahora = Instant.now();

		CatalogoSolicitud solicitud = new CatalogoSolicitud(
				organizationId,
				actor.consultorioId(),
				tipo,
				nombrePropuesto,
				codigoPropuesto,
				justificacion,
				actor.accountId());

		CatalogoSolicitud persistida;
		try {
			persistida = solicitudes.saveAndFlush(solicitud);
		} catch (DataIntegrityViolationException duplicada) {
			// Despues de un flush fallido no se vuelve a tocar la sesion JPA: solo se traduce.
			log.info("Solicitud de catalogo duplicada: organizationId={} tipo={}",
					organizationId, tipo);
			throw new SolicitudDuplicadaException(nombrePropuesto);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("tipo", tipo.name());
		detalles.put("nombrePropuesto", persistida.getNombrePropuesto());
		auditar(AuditEvents.CATALOGO_SOLICITUD_CREATED, persistida, actor,
				null, SolicitudEstado.PENDIENTE.name(), null, detalles, ahora);

		log.info("Solicitud de catalogo creada: organizationId={} solicitudId={}",
				organizationId, persistida.getId());

		return CatalogoSolicitudView.de(persistida);
	}

	/**
	 * Listado de solicitudes.
	 *
	 * <p>El tenant ve las suyas; la plataforma ve todas. Un actor sin contexto y sin rol de
	 * plataforma recibe 403, no una lista vacia: "no elegiste donde trabajas" y "no hay
	 * solicitudes" son dos cosas distintas y la pantalla las muestra distinto.
	 */
	@Transactional(readOnly = true)
	public List<CatalogoSolicitudView> listar(OperatingActor actor, SolicitudEstado estado) {
		String filtro = estado == null ? SIN_FILTRO : estado.name();
		List<CatalogoSolicitud> encontradas = actor.platformAdmin()
				? solicitudes.listarTodas(filtro)
				: solicitudes.listarPorTenant(exigirContexto(actor), filtro);

		return encontradas.stream().map(CatalogoSolicitudView::de).toList();
	}

	/**
	 * Resolucion de una solicitud: aprobarla o rechazarla, con motivo.
	 *
	 * <p><b>Aprobar NO crea el concepto global.</b> Es deliberado: el concepto que la plataforma
	 * termina publicando casi nunca es el que el centro propuso —el codigo se normaliza, el
	 * nombre se unifica con los que ya existen— y crear uno automaticamente a partir del texto
	 * de un pedido llenaria el catalogo comun de duplicados con nombres parecidos, que es
	 * exactamente lo que el circuito de solicitud existe para evitar. La plataforma aprueba, crea
	 * el concepto por el alta normal y, si quiere, deja su id en {@code conceptoId}.
	 *
	 * <p>La version se compara antes de mutar: dos administradores de plataforma sobre la misma
	 * bandeja tienen que enterarse de que el otro llego primero, y no descubrirlo por una fila
	 * que cambio sola.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CatalogoSolicitudView resolver(
			OperatingActor actor, long solicitudId, SolicitudResolucionCommand command) {

		exigirPlataforma(actor);

		CatalogoSolicitud solicitud = solicitudes.findById(solicitudId)
				.orElseThrow(() -> new SolicitudNotAccessibleException(solicitudId));

		if (!solicitud.estaPendiente()) {
			throw new SolicitudYaResueltaException(solicitudId);
		}
		if (solicitud.getVersion() != command.expectedVersion()) {
			throw new OptimisticLockingFailureException(
					"La solicitud fue modificada por otra operacion");
		}

		Instant ahora = Instant.now();
		String estadoAnterior = solicitud.getEstado().name();
		try {
			solicitud.resolver(command.estado(), actor.accountId(), command.nota(), null, ahora);
		} catch (IllegalStateException yaResuelta) {
			throw new SolicitudYaResueltaException(solicitudId);
		}

		CatalogoSolicitud guardada = solicitudes.save(solicitud);

		auditar(AuditEvents.CATALOGO_SOLICITUD_RESOLVED, guardada, actor,
				estadoAnterior, guardada.getEstado().name(), command.nota(), Map.of(), ahora);

		log.info("Solicitud de catalogo resuelta: solicitudId={} estado={}",
				solicitudId, guardada.getEstado());

		return CatalogoSolicitudView.de(guardada);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	private long exigirGestionDelTenant(OperatingActor actor) {
		if (actor.platformAdmin()) {
			// La plataforma no se pide conceptos a si misma: los crea.
			throw new AccessDeniedException(
					"La administracion de plataforma no solicita conceptos: los crea");
		}
		long organizationId = exigirContexto(actor);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	private void exigirPlataforma(OperatingActor actor) {
		if (!actor.platformAdmin()) {
			log.info("Resolucion de solicitud rechazada: accountId={}", actor.accountId());
			throw new AccessDeniedException(
					"Solo la administracion de plataforma resuelve solicitudes de catalogo");
		}
	}

	/** 403 y jamas 401: un 401 haria que el frontend borre el token y entre en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return organizationId;
	}

	private void auditar(
			String eventType,
			CatalogoSolicitud solicitud,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				solicitud.getOrganizationId(),
				solicitud.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_CATALOGO_SOLICITUD,
				solicitud.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
