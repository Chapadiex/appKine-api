package com.akine.organization.application;

import com.akine.organization.domain.Plan;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStateMachine;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Estado y plan de la suscripcion de una organizacion (RF-M01-003, RF-M01-004).
 *
 * <p>Dos operaciones que la spec podria confundir y que aca son distintas:
 * <ul>
 *   <li><b>Transicion de estado</b> — ACTIVA / SUSPENDIDA / CANCELADA, validada contra
 *       {@link SubscriptionStateMachine}. Suspender bloquea, jamas destruye (RN-M01-002).</li>
 *   <li><b>Cambio de plan</b> — no pasa por la maquina de estados y solo se permite con la
 *       suscripcion ACTIVA. Un downgrade NUNCA toca datos existentes (RN-M01-004): el efecto
 *       es prospectivo.</li>
 * </ul>
 *
 * <p>Las dos quedan registradas en {@code subscription_transition}, que es append-only, y las
 * dos auditan dentro de la transaccion (T-2).
 */
@Service
public class SubscriptionService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

	private final SubscriptionRepositoryPort subscriptionRepository;
	private final SubscriptionTransitionRepositoryPort transitionRepository;
	private final OrganizationService organizationService;
	private final PlanCatalogService planCatalogService;
	private final TenantUsageCounter usageCounter;
	private final AuditTrail auditTrail;

	public SubscriptionService(
			SubscriptionRepositoryPort subscriptionRepository,
			SubscriptionTransitionRepositoryPort transitionRepository,
			OrganizationService organizationService,
			PlanCatalogService planCatalogService,
			TenantUsageCounter usageCounter,
			AuditTrail auditTrail) {
		this.subscriptionRepository = subscriptionRepository;
		this.transitionRepository = transitionRepository;
		this.organizationService = organizationService;
		this.planCatalogService = planCatalogService;
		this.usageCounter = usageCounter;
		this.auditTrail = auditTrail;
	}

	/** Suscripcion del tenant con su plan, su consumo y las transiciones posibles. */
	@Transactional(readOnly = true)
	public SubscriptionView find(long organizationId) {
		organizationService.requireActive(organizationId);
		return toView(require(organizationId));
	}

	/**
	 * Aplica una transicion de estado.
	 *
	 * <p><b>Orden de las operaciones.</b> Se valida todo —version esperada, motivo, transicion
	 * permitida— ANTES de escribir cualquier cosa. La transicion invalida lanza desde la
	 * entity, antes del primer {@code save} y antes de la auditoria: una transicion rechazada
	 * no deja ni la suscripcion movida, ni una fila de historico, ni un evento de auditoria
	 * que sugiera que algo paso.
	 *
	 * <p>{@code expectedStatus} implementa el flujo alternativo de la spec: si el estado actual
	 * no es el que el actor creia, se responde 409 y se le pide revalidar, en vez de
	 * sobrescribir en silencio una decision que alguien tomo mientras el miraba la pantalla.
	 *
	 * @param expectedStatus estado que el actor cree vigente, o {@code null} para no chequear
	 * @param reason         obligatorio al suspender y al cancelar: sin el, el historico no
	 *                       sirve para responder "por que dejo de funcionar" seis meses despues
	 * @throws com.akine.organization.domain.exception.InvalidSubscriptionTransitionException
	 *         si la maquina de estados no admite el salto, incluido repetir el actual (409)
	 * @throws OptimisticLockingFailureException si el estado actual no es el esperado (409)
	 * @throws IllegalArgumentException si falta el motivo donde es obligatorio (400)
	 */
	@Transactional
	public SubscriptionView transition(
			long organizationId,
			SubscriptionStatus toStatus,
			SubscriptionStatus expectedStatus,
			String reason,
			Long actorAccountId) {

		organizationService.requireActive(organizationId);
		Subscription subscription = require(organizationId);

		if (expectedStatus != null && subscription.getStatus() != expectedStatus) {
			throw new OptimisticLockingFailureException(
					"El estado de la suscripcion cambio: revalidar antes de reintentar");
		}
		if (SubscriptionStateMachine.requiresReason(toStatus)
				&& (reason == null || reason.isBlank())) {
			throw new IllegalArgumentException(
					"La transicion a " + toStatus + " exige un motivo declarado");
		}

		SubscriptionStatus from = subscription.getStatus();
		subscription.transitionTo(toStatus);
		subscriptionRepository.save(subscription);

		transitionRepository.save(new SubscriptionTransition(
				organizationId,
				subscription.getId(),
				from,
				toStatus,
				subscription.getPlanId(),
				subscription.getPlanId(),
				reason,
				actorAccountId,
				Instant.now()));

		Map<String, String> details = new LinkedHashMap<>();
		details.put("fromStatus", from.name());
		details.put("toStatus", toStatus.name());
		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				actorAccountId,
				AuditEvents.SUBSCRIPTION_TRANSITIONED,
				AuditEvents.ENTITY_SUBSCRIPTION,
				subscription.getId(),
				from.name(),
				toStatus.name(),
				details,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));

		log.info("Suscripcion transicionada: organizationId={} {} -> {}",
				organizationId, from, toStatus);

		return toView(subscription);
	}

	/**
	 * Cambia el plan contratado.
	 *
	 * <p><b>Un downgrade se permite aunque el uso actual supere los limites nuevos, y no toca
	 * un solo dato</b> (RN-M01-004): no borra, no desactiva y no marca nada como invalido. Lo
	 * que ya existe sigue operativo y consultable. Lo unico que cambia es el futuro: la
	 * proxima alta que exceda el limite nuevo se rechaza en el gate. Los limites ya excedidos
	 * vuelven como {@code warnings} para que el cliente pueda ordenarse antes de chocarse, no
	 * como un rechazo.
	 *
	 * <p>Reintentar un cambio ya aplicado no genera una segunda fila de historico ni un segundo
	 * evento: se detecta que el plan pedido ya es el vigente y se responde con
	 * {@code applied = false}. Un reintento no puede producir un segundo efecto.
	 *
	 * @throws com.akine.organization.domain.exception.SubscriptionSuspendedException si la
	 *         suscripcion no esta ACTIVA (409)
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409)
	 * @throws PlanNotFoundException si el plan no existe o ya no es contratable (404)
	 */
	@Transactional
	public PlanChangeOutcome changePlan(
			long organizationId, String planCode, long expectedVersion, Long actorAccountId) {

		organizationService.requireActive(organizationId);
		Plan nuevoPlan = planCatalogService.requireContractable(planCode);
		Subscription subscription = require(organizationId);

		if (subscription.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La suscripcion fue modificada por otra operacion");
		}

		if (nuevoPlan.getId().equals(subscription.getPlanId())) {
			return new PlanChangeOutcome(
					toView(subscription), warningsFor(organizationId, nuevoPlan.getId()), false);
		}

		Long planAnterior = subscription.getPlanId();
		String codigoAnterior = planCatalogService.requireById(planAnterior).getCode();

		subscription.changePlan(nuevoPlan.getId());
		subscriptionRepository.save(subscription);

		transitionRepository.save(new SubscriptionTransition(
				organizationId,
				subscription.getId(),
				subscription.getStatus(),
				subscription.getStatus(),
				planAnterior,
				nuevoPlan.getId(),
				null,
				actorAccountId,
				Instant.now()));

		List<LimitUsageView> warnings = warningsFor(organizationId, nuevoPlan.getId());

		Map<String, String> details = new LinkedHashMap<>();
		details.put("fromPlan", codigoAnterior);
		details.put("toPlan", nuevoPlan.getCode());
		details.put("limitesExcedidos", String.valueOf(warnings.size()));
		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				actorAccountId,
				AuditEvents.SUBSCRIPTION_PLAN_CHANGED,
				AuditEvents.ENTITY_SUBSCRIPTION,
				subscription.getId(),
				codigoAnterior,
				nuevoPlan.getCode(),
				details,
				null,
				AuditEvents.correlationId(),
				Instant.now()));

		log.info("Plan cambiado: organizationId={} {} -> {} limitesExcedidos={}",
				organizationId, codigoAnterior, nuevoPlan.getCode(), warnings.size());

		return new PlanChangeOutcome(toView(subscription), warnings, true);
	}

	/** Historico de la suscripcion, del hecho mas reciente al mas viejo. */
	@Transactional(readOnly = true)
	public Page<SubscriptionTransitionView> history(long organizationId, Pageable pageable) {
		organizationService.requireActive(organizationId);
		Subscription subscription = require(organizationId);
		return transitionRepository
				.findAllByOrganizationIdAndSubscriptionIdOrderByOccurredAtDesc(
						organizationId, subscription.getId(), pageable)
				.map(this::toTransitionView);
	}

	/**
	 * Limites del plan que el uso actual YA excede.
	 *
	 * <p>Se lee sin bloqueo porque es informativo: un aviso que quede viejo un instante despues
	 * sigue siendo util. El conteo que DECIDE un alta es otro y vive en el gate, dentro de la
	 * transaccion del alta y despues del bloqueo de la suscripcion.
	 */
	private List<LimitUsageView> warningsFor(long organizationId, long planId) {
		List<LimitUsageView> warnings = new ArrayList<>();
		for (PlanLimitView limit : planCatalogService.limitsOf(planId)) {
			long uso = usageCounter.count(limit.code(), organizationId);
			LimitUsageView usage = new LimitUsageView(limit.code(), limit.value(), uso);
			if (usage.exceeded()) {
				warnings.add(usage);
			}
		}
		return warnings;
	}

	private Subscription require(long organizationId) {
		return subscriptionRepository.findByOrganizationId(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	private SubscriptionView toView(Subscription subscription) {
		Plan plan = planCatalogService.requireById(subscription.getPlanId());
		List<LimitUsageView> limits = planCatalogService.limitsOf(plan.getId()).stream()
				.map(limit -> new LimitUsageView(
						limit.code(),
						limit.value(),
						usageCounter.count(limit.code(), subscription.getOrganizationId())))
				.toList();
		return new SubscriptionView(
				subscription.getId(),
				subscription.getOrganizationId(),
				plan.getCode(),
				plan.getName(),
				subscription.getStatus(),
				subscription.getStartedAt(),
				subscription.getVersion(),
				limits,
				SubscriptionStateMachine.allowedTargets(subscription.getStatus()));
	}

	private SubscriptionTransitionView toTransitionView(SubscriptionTransition transition) {
		return new SubscriptionTransitionView(
				transition.getId(),
				transition.getFromStatus(),
				transition.getToStatus(),
				codeOf(transition.getFromPlanId()),
				codeOf(transition.getToPlanId()),
				transition.getReason(),
				transition.getActorAccountId(),
				transition.getOccurredAt());
	}

	private String codeOf(Long planId) {
		return planId == null ? null : planCatalogService.requireById(planId).getCode();
	}
}
