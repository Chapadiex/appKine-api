package com.akine.organization.application;

import com.akine.organization.domain.SupportAccess;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.SupportAccessNotFoundException;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SupportAccessRepositoryPort;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceso de soporte: la unica via por la que un {@code PLATFORM_ADMIN} entra a los datos de un
 * tenant (matriz §3 "Soporte", §7, ADR-0020).
 *
 * <h2>El modelo, tal como el usuario lo decidio el 23/08/2026 (D-3)</h2>
 *
 * <ul>
 *   <li><b>Autoconcedido, con motivo declarado.</b> Lo pide el propio administrador de
 *       plataforma. Es el mas debil de los tres modelos evaluados y es el unico operable: el de
 *       cuatro ojos exige dos personas de plataforma disponibles a las tres de la mañana, y el
 *       aprobado por el {@code ORG_ADMIN} del tenant no sirve para el caso que motiva casi todo
 *       el soporte, que es "el tenant no puede entrar".</li>
 *   <li><b>Cuatro horas.</b> Ver {@link SupportAccess#VIGENCIA_POR_DEFECTO}.</li>
 *   <li><b>Auditado, y el tenant se entera.</b> El otorgamiento escribe
 *       {@code SUPPORT_ACCESS_GRANTED} con el {@code organization_id} del tenant, asi que
 *       aparece en la propia consulta de auditoria de su {@code ORG_ADMIN}. Ademas, CADA
 *       operacion realizada al amparo del acceso deja {@code SUPPORT_ACCESS_USED} —lo escribe
 *       quien ejecuta la operacion, porque es el unico que sabe cual fue—.</li>
 * </ul>
 *
 * <h2>Lo que falta, y por que no esta</h2>
 *
 * <p>El aviso <b>por correo</b> al {@code ORG_ADMIN} no se implementa en esta ola y no es un
 * olvido: para mandarlo hay que resolver el email de una cuenta, y {@code cuenta} es de
 * {@code identity}. {@code organization} no puede compilar contra ese modulo (regla heredada de
 * 01.01, verificada por ArchUnit) y {@code notification.spi.NotificationEnqueueCommand} exige un
 * destinatario concreto. Las dos salidas —que la orquestacion viva en {@code identity}, como el
 * onboarding compuesto, o un puerto nuevo que resuelva destinatarios por rol— son decisiones de
 * diseño que exceden esta tarea. Hasta entonces el aviso es el evento de auditoria, que el
 * tenant si puede ver.
 */
@Service
public class SupportAccessService {

	private static final Logger log = LoggerFactory.getLogger(SupportAccessService.class);

	private final SupportAccessRepositoryPort supportAccessRepository;
	private final OrganizationRepositoryPort organizationRepository;
	private final PermissionEvaluator permissionEvaluator;
	private final AuditTrail auditTrail;

	public SupportAccessService(
			SupportAccessRepositoryPort supportAccessRepository,
			OrganizationRepositoryPort organizationRepository,
			PermissionEvaluator permissionEvaluator,
			AuditTrail auditTrail) {
		this.supportAccessRepository = supportAccessRepository;
		this.organizationRepository = organizationRepository;
		this.permissionEvaluator = permissionEvaluator;
		this.auditTrail = auditTrail;
	}

	/**
	 * Concede un acceso de soporte sobre un tenant.
	 *
	 * <p><b>El rol de plataforma se revalida aca contra la base</b> y no se cree del booleano que
	 * llega de la capa {@code api}: esta es la operacion que abre la puerta a los datos de un
	 * tenant, asi que la comprobacion tiene que ser de primera mano.
	 *
	 * @param duracion duracion pedida, o {@code null} para {@link SupportAccess#VIGENCIA_POR_DEFECTO}
	 * @throws AccessDeniedException si el actor no es administrador de plataforma (403)
	 * @throws OrganizationNotFoundException si la organizacion no existe o esta dada de baja (404)
	 * @throws IllegalArgumentException si falta el motivo, o la duracion excede el maximo (400)
	 */
	@Transactional
	public SupportAccessView grant(
			long actorAccountId, long organizationId, String motivo, Duration duracion) {

		Instant ahora = Instant.now();
		if (!permissionEvaluator.isPlatformAdmin(actorAccountId, ahora)) {
			throw new AccessDeniedException(
					"El acceso de soporte es una operacion de administracion de plataforma");
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"El acceso de soporte exige un motivo declarado: sin motivo no hay soporte, "
							+ "hay acceso a datos de salud ajenos");
		}
		organizationRepository.findByIdAndActiveTrue(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));

		Duration efectiva = duracion == null ? SupportAccess.VIGENCIA_POR_DEFECTO : duracion;
		if (efectiva.isNegative() || efectiva.isZero()
				|| efectiva.compareTo(SupportAccess.VIGENCIA_POR_DEFECTO) > 0) {
			// El tope es la decision del usuario, no un default sugerido: dejar que quien pide
			// el acceso elija su propia duracion convertiria "acotado en tiempo" en "acotado si
			// el que entra quiere".
			throw new IllegalArgumentException(
					"La duracion del acceso de soporte debe ser positiva y no puede exceder "
							+ SupportAccess.VIGENCIA_POR_DEFECTO);
		}

		SupportAccess acceso = supportAccessRepository.save(new SupportAccess(
				organizationId, actorAccountId, motivo, actorAccountId, ahora, ahora.plus(efectiva)));

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("validUntil", acceso.getValidUntil().toString());
		// organizationId poblado: es lo que hace que el ORG_ADMIN del tenant vea el acceso en su
		// propia auditoria. Con null seria un evento de plataforma y el tenant no se enteraria.
		auditTrail.record(new AuditEntry(
				organizationId, null, actorAccountId,
				AuditEvents.SUPPORT_ACCESS_GRANTED, AuditEvents.ENTITY_SUPPORT_ACCESS,
				acceso.getId(), null, null, detalles, motivo,
				AuditEvents.correlationId(), ahora));

		log.info("Acceso de soporte concedido: organizationId={} accountId={} validUntil={}",
				organizationId, actorAccountId, acceso.getValidUntil());
		return SupportAccessView.de(acceso);
	}

	/**
	 * Revoca un acceso de soporte antes de su vencimiento.
	 *
	 * <p>No borra: cierra. Que alguien haya entrado a un tenant y por que tiene que seguir siendo
	 * legible despues.
	 *
	 * @throws SupportAccessNotFoundException si no existe o ya estaba cerrado (404)
	 */
	@Transactional
	public void revoke(long actorAccountId, long supportAccessId) {
		Instant ahora = Instant.now();
		if (!permissionEvaluator.isPlatformAdmin(actorAccountId, ahora)) {
			throw new AccessDeniedException(
					"El acceso de soporte es una operacion de administracion de plataforma");
		}

		SupportAccess acceso = supportAccessRepository.findByIdAndActiveTrue(supportAccessId)
				.orElseThrow(() -> new SupportAccessNotFoundException(supportAccessId));

		acceso.revoke(actorAccountId, ahora);
		supportAccessRepository.save(acceso);

		auditTrail.record(new AuditEntry(
				acceso.getOrganizationId(), null, actorAccountId,
				AuditEvents.SUPPORT_ACCESS_REVOKED, AuditEvents.ENTITY_SUPPORT_ACCESS,
				acceso.getId(), null, null, Map.of(), null,
				AuditEvents.correlationId(), ahora));
	}

	/**
	 * Accesos de soporte de un tenant, vigentes e historicos.
	 *
	 * <p>Lo consulta la administracion de plataforma. La misma informacion le llega al tenant por
	 * su auditoria, que es donde tiene sentido para el.
	 */
	@Transactional(readOnly = true)
	public List<SupportAccessView> list(long actorAccountId, long organizationId) {
		if (!permissionEvaluator.isPlatformAdmin(actorAccountId, Instant.now())) {
			throw new AccessDeniedException(
					"El acceso de soporte es una operacion de administracion de plataforma");
		}
		return supportAccessRepository.findAllByOrganizationIdAndActiveTrue(organizationId).stream()
				.map(SupportAccessView::de)
				.toList();
	}
}
