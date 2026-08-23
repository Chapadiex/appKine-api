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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
	private final TransactionTemplate altaLimitada;

	public PlanGateService(
			OrganizationRepositoryPort organizationRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			PlanLimitRepositoryPort planLimitRepository,
			PlanFeatureRepositoryPort planFeatureRepository,
			PlanLimitRejectionAuditor rejectionAuditor,
			PlatformTransactionManager transactionManager) {
		this.organizationRepository = organizationRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.planLimitRepository = planLimitRepository;
		this.planFeatureRepository = planFeatureRepository;
		this.rejectionAuditor = rejectionAuditor;
		this.altaLimitada = altaLimitada(transactionManager);
	}

	/**
	 * La transaccion del alta limitada: la unica del sistema con READ COMMITTED.
	 *
	 * <p>Es donde vive el arreglo del bug de visibilidad que se explica en
	 * {@link PlanGate#createWithinLimit}. Se fija aca y no en la aplicacion entera porque el
	 * unico protocolo que depende de ver lo que el bloqueo acaba de dejar pasar es este.
	 */
	private static TransactionTemplate altaLimitada(PlatformTransactionManager transactionManager) {
		TransactionTemplate plantilla = new TransactionTemplate(transactionManager);
		plantilla.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		plantilla.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		return plantilla;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Se rechaza de entrada si ya hay una transaccion abierta. No es purismo: al unirse a
	 * una transaccion existente <b>Spring ignora la isolation declarada</b> —la fija quien la
	 * abre—, asi que el alta correria en REPEATABLE READ y el conteo de abajo volveria a leer
	 * de un snapshot anterior al bloqueo. Fallaria en silencio, dejando entrar altas de mas,
	 * que es exactamente el bug que este metodo cierra. Mejor ruidoso.
	 */
	@Override
	public <T> T createWithinLimit(
			long organizationId,
			LimitCode limit,
			LongSupplier currentUsageCounter,
			Supplier<T> creation) {

		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
					"createWithinLimit abre su propia transaccion con READ COMMITTED y no puede "
							+ "unirse a una existente: al unirse heredaria la isolation del "
							+ "llamador y el conteo del limite volveria a leer de un snapshot "
							+ "viejo. Invocalo fuera de toda transaccion.");
		}

		return altaLimitada.execute(status -> {
			evaluateCreationAndLock(organizationId, limit, currentUsageCounter);
			return creation.get();
		});
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
	 *
	 * <p><b>Y el orden, solo, tampoco alcanza.</b> Quien lea el {@code SELECT ... FOR UPDATE}
	 * de abajo va a suponer —como supuso el autor— que con eso el limite queda cerrado. No
	 * queda: en REPEATABLE READ el bloqueo serializa el ACCESO y no la VISIBILIDAD. El hilo
	 * que espera en el bloqueo entra recien cuando el otro commiteo, pero el conteo del paso 3
	 * es una lectura consistente y lee del snapshot que fijo la primera lectura no bloqueante
	 * de la transaccion —la del paso 1—, anterior a ese commit. Cuenta de menos y deja pasar
	 * un alta de mas, sin error y sin rastro. Por eso la transaccion tiene que correr en READ
	 * COMMITTED, y por eso existe {@link #createWithinLimit}, que es quien la abre asi.
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public PlanDecision evaluateCreationAndLock(
			long organizationId, LimitCode limit, LongSupplier currentUsageCounter) {

		// 0. La isolation no se puede declarar aca: MANDATORY se une a la transaccion del
		//    llamador y el atributo se ignora al unirse. Lo unico que se puede hacer es
		//    verificarla y negarse ruidosamente, en vez de dejar pasar altas de mas en
		//    silencio. Sin transaccion activa no se comprueba nada: eso ya lo cubre MANDATORY
		//    en produccion, y en los tests unitarios este servicio corre sin proxy.
		verificarIsolation();

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
	 * Exige que la transaccion en curso NO sea REPEATABLE READ.
	 *
	 * <p>{@code ISOLATION_DEFAULT} —que Spring representa como {@code null}— tambien se rechaza:
	 * significa "la del motor", y en MySQL la del motor es justamente REPEATABLE READ. Adivinar
	 * ahi seria adivinar en la direccion equivocada.
	 *
	 * <p><b>Lo que se lee aca es la isolation DECLARADA en la transaccion, no la real de la
	 * conexion.</b> El dia que alguien fije READ COMMITTED en el datasource
	 * ({@code spring.datasource.hikari.transaction-isolation}) o en el servidor, un
	 * {@code @Transactional} corriente va a seguir declarando {@code DEFAULT} y este guard lo
	 * va a rechazar aunque en la practica sea seguro. Es el lado en el que conviene
	 * equivocarse: un 500 ruidoso en desarrollo contra altas de mas en produccion.
	 */
	private void verificarIsolation() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
		boolean visibilidadSuficiente = isolation != null
				&& (isolation == TransactionDefinition.ISOLATION_READ_COMMITTED
						|| isolation == TransactionDefinition.ISOLATION_SERIALIZABLE);
		if (!visibilidadSuficiente) {
			throw new IllegalStateException(
					"La evaluacion de limite de plan exige READ COMMITTED en la transaccion que "
							+ "la contiene: en REPEATABLE READ el conteo posterior al bloqueo lee "
							+ "de un snapshot anterior y el limite se viola en silencio. Usa "
							+ "PlanGate.createWithinLimit, que abre la transaccion correcta.");
		}
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
