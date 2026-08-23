package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.OrganizationOnboarding;
import com.akine.organization.domain.Plan;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.OrganizationOnboardingRepositoryPort;
import com.akine.organization.spi.InitialOrganizationCommand;
import com.akine.organization.spi.InitialOrganizationProvisioning;
import com.akine.organization.spi.OnboardingKeyTakenException;
import com.akine.organization.spi.ProvisioningResult;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Alta compuesta de una organizacion (ADR-0008): Organizacion + Consultorio + Suscripcion +
 * Membership del fundador, en UNA transaccion.
 *
 * <p>Implementa {@link InitialOrganizationProvisioning}, que es lo que ve {@code identity}. El
 * contrato explica por que la propagacion es {@code REQUIRED}; aca se hace cumplir.
 */
@Service
public class OnboardingService implements InitialOrganizationProvisioning {

	private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

	private final OrganizationOnboardingRepositoryPort onboardingRepository;
	private final MembershipRepositoryPort membershipRepository;
	private final OrganizationService organizationService;
	private final PlanCatalogService planCatalogService;
	private final AuditTrail auditTrail;

	public OnboardingService(
			OrganizationOnboardingRepositoryPort onboardingRepository,
			MembershipRepositoryPort membershipRepository,
			OrganizationService organizationService,
			PlanCatalogService planCatalogService,
			AuditTrail auditTrail) {
		this.onboardingRepository = onboardingRepository;
		this.membershipRepository = membershipRepository;
		this.organizationService = organizationService;
		this.planCatalogService = planCatalogService;
		this.auditTrail = auditTrail;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Solo lee. Se ejecuta en la transaccion del llamador si ya hay una —lo normal, porque
	 * quien invoca esto es el registro self-service justo antes de decidir— y abre una de
	 * lectura si no.
	 */
	@Override
	@Transactional(readOnly = true, propagation = Propagation.REQUIRED)
	public void validateTenantRequest(String planCode, String organizationSlug) {
		planCatalogService.requireContractable(planCode == null || planCode.isBlank()
				? InitialOrganizationCommand.PLAN_POR_DEFECTO
				: planCode);
		organizationService.requireSlugDisponible(organizationSlug);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>{@code Propagation.REQUIRED} y jamas {@code REQUIRES_NEW}.</b> Este metodo se
	 * ejecuta dentro de la transaccion que {@code identity} ya abrio para crear la cuenta. Con
	 * {@code REQUIRES_NEW} las escrituras de aca commitearian por su cuenta y un fallo
	 * posterior en {@code identity} —al guardar la credencial, al encolar el mail— revertiria
	 * la cuenta dejando organizacion, consultorio y membership creados y apuntando a un
	 * {@code account_id} que no existe. Ese tenant huerfano no se detecta hasta que alguien
	 * intenta entrar, y para entonces no hay forma automatica de saber si se completa o se
	 * borra. ADR-0008 exige lo contrario: las cinco escrituras viven o mueren juntas.
	 *
	 * <p>{@code REQUIRED} tambien cubre el caso de invocacion sin transaccion previa —un job,
	 * un test— abriendo una: el alta sigue siendo atomica.
	 */
	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public ProvisioningResult provision(InitialOrganizationCommand command) {
		// 1. Camino feliz del reintento: la clave ya se uso y devolvemos lo mismo de antes.
		Optional<OrganizationOnboarding> previo =
				onboardingRepository.findByIdempotencyKey(command.idempotencyKey());
		if (previo.isPresent()) {
			return replayDe(previo.get(), command);
		}

		// 2. Las mismas dos validaciones que hace validateTenantRequest —el llamador ya deberia
		//    haberlas corrido ANTES de mirar el email, ver el spi—, aca desplegadas porque el
		//    plan hace falta como entity y buscarlo dos veces seria un seek al pedo. Van
		//    DESPUES del replay a proposito: un alta ya completada tiene que poder reproducirse
		//    aunque el plan que contrato se haya retirado despues del catalogo.
		organizationService.requireSlugDisponible(command.organizationSlug());
		Plan plan = planCatalogService.requireContractable(command.planCodeOrDefault());

		ProvisionedTenant tenant = organizationService.provisionTenant(
				command.organizationName(),
				command.organizationSlug(),
				null,
				plan,
				command.consultorioNameOrDefault(),
				command.accountId());

		Instant ahora = Instant.now();

		// La membership del propietario: ORG_ADMIN + is_founder (T-5). OWNER y ADMIN no existen
		// en la matriz aprobada, y RN-M05-006 prohibe crear un rol nuevo para distinguir al
		// fundador: esa distincion es un ATRIBUTO de la membership, que es otra dimension.
		// consultorioId = null porque el propietario alcanza toda la organizacion, no una sede.
		Membership membership = membershipRepository.save(new Membership(
				tenant.organization().getId(),
				null,
				command.accountId(),
				RoleCode.ORG_ADMIN,
				true,
				ahora));

		// 3. El registro de idempotencia se inserta CON FLUSH y al final, con los ids ya
		//    asignados. El flush es lo que hace que la violacion del unique se manifieste aca
		//    dentro y no al cerrar la transaccion, cuando ya no habria a quien avisarle.
		try {
			onboardingRepository.saveAndFlush(new OrganizationOnboarding(
					command.idempotencyKey(),
					command.requestHash(),
					command.accountId(),
					tenant.organization().getId(),
					tenant.consultorio().getId(),
					membership.getId(),
					ahora));
		} catch (DataIntegrityViolationException colision) {
			// 4. Reintento CONCURRENTE: otro hilo con la misma clave gano la carrera entre
			//    nuestro SELECT del paso 1 y este INSERT. Quien decide es uk_onboarding_key, no
			//    un chequeo previo: dos hilos simultaneos leen ambos "no existe", asi que un
			//    SELECT solo seria la misma carrera con otro nombre. Ese razonamiento sigue en
			//    pie; lo que no se puede es RECUPERARSE aca.
			//
			//    Antes se releia la fila del ganador con findByIdempotencyKey y se hacia el
			//    replay. Sobre la sesion equivocada: el flush de arriba ya fallo, y despues de
			//    un flush fallido el EntityManager queda en estado indefinido —la
			//    especificacion de JPA prohibe seguir usandolo—. Lo que salia de ahi era un
			//    AssertionFailure o un "Transaction marked as rollbackOnly", es decir un 500,
			//    alcanzable con la misma Idempotency-Key y dos emails distintos, y otra vez
			//    rompiendo la uniformidad del 202 de ADR-0018.
			//
			//    El unico movimiento legal es revertir y empezar de nuevo en una sesion limpia,
			//    y esa decision no es de este modulo: provision corre con REQUIRED dentro de la
			//    transaccion del llamador (ADR-0008), asi que el dueno del limite transaccional
			//    es el unico que puede abrir uno nuevo. La senal sale por el spi y el llamador
			//    resuelve. Ver OnboardingKeyTakenException.
			//
			//    Y no se confunde con uk_organization_slug: esa colision ya se separo en su
			//    origen, dentro de provisionTenant, y sale como OrganizationSlugTakenException.
			//    Son dos causas distintas con dos respuestas distintas —un error del cliente y
			//    una carrera cuyo desenlace correcto es el 202 del ganador— y tratarlas igual
			//    daba la respuesta equivocada en las dos.
			log.info("Alta compuesta concurrente detectada: gano el otro hilo, se revierte esta");
			throw new OnboardingKeyTakenException(command.idempotencyKey());
		}

		auditarMembership(tenant.organization().getId(), tenant.consultorio().getId(),
				membership, command.accountId(), ahora);

		log.info("Alta compuesta completada: organizationId={} consultorioId={} membershipId={}",
				tenant.organization().getId(), tenant.consultorio().getId(), membership.getId());

		return new ProvisioningResult(
				tenant.organization().getId(),
				tenant.consultorio().getId(),
				membership.getId(),
				true);
	}

	/**
	 * Devuelve el resultado del alta original.
	 *
	 * <p>Antes compara el hash del payload: la misma clave con un contenido distinto es un
	 * error del cliente, no un reintento, y devolverle el resultado viejo lo dejaria creyendo
	 * que se creo lo que pidio ahora. Cuando el registro no guarda hash —alta por {@code spi},
	 * donde no hay payload HTTP que comparar— se acepta el replay: inventar un conflicto ahi
	 * romperia el onboarding self-service.
	 */
	private ProvisioningResult replayDe(
			OrganizationOnboarding registro, InitialOrganizationCommand command) {

		if (!registro.matchesRequestHash(command.requestHash())) {
			throw new IdempotencyKeyConflictException(command.idempotencyKey());
		}
		return new ProvisioningResult(
				registro.getOrganizationId(),
				registro.getConsultorioId(),
				registro.getMembershipId(),
				false);
	}

	/**
	 * Audita el alta de la membership del fundador.
	 *
	 * <p>Dentro de la transaccion (T-2): si el registro falla, el alta entera no se confirma.
	 * Los detalles son el rol y la condicion de fundador —informacion de autorizacion, no
	 * datos personales—; el {@code accountId} ya viaja en su propio campo.
	 */
	private void auditarMembership(
			long organizationId, long consultorioId, Membership membership,
			long accountId, Instant ahora) {

		Map<String, String> details = new LinkedHashMap<>();
		details.put("roleCode", membership.getRoleCode().name());
		details.put("isFounder", String.valueOf(membership.isFounder()));
		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				accountId,
				AuditEvents.MEMBERSHIP_CREATED,
				AuditEvents.ENTITY_MEMBERSHIP,
				membership.getId(),
				null,
				membership.getRoleCode().name(),
				details,
				null,
				AuditEvents.correlationId(),
				ahora));
	}
}
