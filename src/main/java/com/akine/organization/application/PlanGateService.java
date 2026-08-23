package com.akine.organization.application;

import com.akine.organization.domain.Organization;
import com.akine.organization.domain.PlanLimit;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.exception.FeatureNotAvailableException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.PlanFeatureRepositoryPort;
import com.akine.organization.domain.port.PlanLimitRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.FeatureCode;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.PlanDecision;
import com.akine.organization.spi.PlanGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Implementacion del control de plan (RF-M01-004).
 *
 * <p>Toda la explicacion de por que la evaluacion tiene esta forma —bloqueo pesimista sobre la
 * suscripcion y conteo posterior— esta en el JavaDoc de {@link PlanGate}, que es el contrato
 * que ven los otros modulos. Aca solo se hace cumplir.
 */
@Service
public class PlanGateService implements PlanGate {

	private static final Logger log = LoggerFactory.getLogger(PlanGateService.class);

	private final OrganizationRepositoryPort organizationRepository;
	private final SubscriptionRepositoryPort subscriptionRepository;
	private final PlanLimitRepositoryPort planLimitRepository;
	private final PlanFeatureRepositoryPort planFeatureRepository;
	private final PlanLimitRejectionAuditor rejectionAuditor;

	public PlanGateService(
			OrganizationRepositoryPort organizationRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			PlanLimitRepositoryPort planLimitRepository,
			PlanFeatureRepositoryPort planFeatureRepository,
			PlanLimitRejectionAuditor rejectionAuditor) {
		this.organizationRepository = organizationRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.planLimitRepository = planLimitRepository;
		this.planFeatureRepository = planFeatureRepository;
		this.rejectionAuditor = rejectionAuditor;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>{@code Propagation.MANDATORY} es parte de la correccion, no una preferencia.</b>
	 * El bloqueo pesimista dura lo que dura la transaccion que lo toma. Si este metodo abriera
	 * una transaccion propia ({@code REQUIRED} sin transaccion previa, o peor,
	 * {@code REQUIRES_NEW}), el {@code SELECT ... FOR UPDATE} se liberaria al volver del
	 * metodo y el alta del consumidor ocurriria sin ninguna serializacion: exactamente la
	 * carrera que el bloqueo existe para cerrar, con el costo del bloqueo y ningun beneficio.
	 * {@code MANDATORY} hace que ese uso falle de inmediato y de forma ruidosa, en vez de
	 * funcionar mal en silencio.
	 *
	 * <p>El orden de las lineas de abajo es la regla, no un detalle de estilo: bloquear,
	 * <b>despues</b> contar, despues decidir.
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public PlanDecision evaluateCreationAndLock(
			long organizationId, LimitCode limit, LongSupplier currentUsageCounter) {

		// 1. La organizacion tiene que existir y estar vigente. Inexistente y de otro tenant
		//    se responden igual: distinguirlos permitiria enumerar clientes del SaaS.
		organizationRepository.findByIdAndActiveTrue(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));

		// 2. Punto de serializacion: SELECT ... FOR UPDATE sobre la unica fila de suscripcion
		//    del tenant. A partir de aca, ninguna otra alta de ESTA organizacion avanza.
		Subscription subscription = subscriptionRepository
				.findByOrganizationIdForUpdate(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));

		if (!subscription.allowsBusinessMutations()) {
			throw new SubscriptionSuspendedException(organizationId);
		}

		// 3. Recien ahora se cuenta. Contar antes del paso 2 —o peor, antes de la transaccion—
		//    permite que dos altas concurrentes lean el mismo numero y pasen las dos.
		long currentUsage = currentUsageCounter.getAsLong();

		// 4. Decidir. Sin fila de limite el limite no aplica a este plan; con limitValue nulo
		//    es ilimitado explicito. No son el mismo caso, pero los dos permiten.
		Optional<PlanLimit> configurado = planLimitRepository
				.findByPlanIdAndLimitCodeAndActiveTrue(subscription.getPlanId(), limit);

		if (configurado.isEmpty()) {
			return new PlanDecision(limit, null, currentUsage, true);
		}

		PlanLimit planLimit = configurado.get();
		if (!planLimit.allows(currentUsage)) {
			log.info("Alta rechazada por limite de plan: organizationId={} limit={} tope={} uso={}",
					organizationId, limit, planLimit.getLimitValue(), currentUsage);
			rejectionAuditor.recordRejection(
					organizationId, limit, planLimit.getLimitValue(), currentUsage);
			throw new PlanLimitExceededException(limit, planLimit.getLimitValue(), currentUsage);
		}

		return new PlanDecision(limit, planLimit.getLimitValue(), currentUsage, true);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Sin bloqueo y en solo lectura: una feature es presencia o ausencia de una fila, no un
	 * conteo, y por lo tanto no hay nada que serializar. Bloquear aca solo agregaria
	 * contencion entre operaciones que no compiten.
	 */
	@Override
	@Transactional(readOnly = true)
	public void requireFeature(long organizationId, FeatureCode feature) {
		Subscription subscription = subscriptionRepository.findByOrganizationId(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));

		boolean incluida = planFeatureRepository
				.existsByPlanIdAndFeatureCodeAndActiveTrue(subscription.getPlanId(), feature);

		if (!incluida) {
			log.info("Funcionalidad no incluida en el plan: organizationId={} feature={}",
					organizationId, feature);
			throw new FeatureNotAvailableException(feature);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>La organizacion dada de baja gana sobre cualquier estado de suscripcion: por eso se
	 * consultan las dos cosas y no alcanza con mirar la suscripcion.
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean allowsBusinessMutations(long organizationId) {
		Optional<Organization> organization =
				organizationRepository.findByIdAndActiveTrue(organizationId);
		if (organization.isEmpty()) {
			return false;
		}
		return subscriptionRepository.findByOrganizationId(organizationId)
				.map(Subscription::allowsBusinessMutations)
				.orElse(false);
	}
}
