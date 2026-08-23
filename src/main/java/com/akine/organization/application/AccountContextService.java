package com.akine.organization.application;

import com.akine.organization.domain.AccountActiveContext;
import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.Membership;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.ContextNotAuthorizedException;
import com.akine.organization.domain.port.AccountActiveContextRepositoryPort;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ActiveContext;
import com.akine.organization.spi.AuthorizedContext;
import com.akine.organization.spi.MembershipSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolucion y seleccion del contexto de trabajo de una cuenta (RF-M01-005, ADR-0009).
 *
 * <p>Implementa {@link AccountContextDirectory}, que es lo unico que ven los otros modulos.
 *
 * <p><b>Sin cache, a proposito.</b> La vigencia de una membership se pregunta contra la base
 * cada vez. Cachearla abriria una ventana en la que un acceso revocado sigue funcionando, y
 * sobre datos clinicos esa ventana no es aceptable: el costo de un seek indexado por request
 * es preferible (RN-M01-003, T-7).
 *
 * <p>Referencias a cuentas por {@code accountId} y nada mas (T-1): {@code organization} jamas
 * compila contra {@code identity}.
 */
@Service
public class AccountContextService implements AccountContextDirectory {

	private static final Logger log = LoggerFactory.getLogger(AccountContextService.class);

	private final MembershipRepositoryPort membershipRepository;
	private final OrganizationRepositoryPort organizationRepository;
	private final ConsultorioRepositoryPort consultorioRepository;
	private final SubscriptionRepositoryPort subscriptionRepository;
	private final AccountActiveContextRepositoryPort activeContextRepository;
	private final AuditTrail auditTrail;

	public AccountContextService(
			MembershipRepositoryPort membershipRepository,
			OrganizationRepositoryPort organizationRepository,
			ConsultorioRepositoryPort consultorioRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			AccountActiveContextRepositoryPort activeContextRepository,
			AuditTrail auditTrail) {
		this.membershipRepository = membershipRepository;
		this.organizationRepository = organizationRepository;
		this.consultorioRepository = consultorioRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.activeContextRepository = activeContextRepository;
		this.auditTrail = auditTrail;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>La vigencia temporal se evalua en Java con {@link Membership#isValidAt(Instant)} y no
	 * en el {@code WHERE}: asi la regla vive en un solo lugar, se testea sin base de datos y
	 * no depende del reloj del motor, que puede no ser el del backend.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<AuthorizedContext> authorizedContexts(long accountId) {
		Instant ahora = Instant.now();
		List<AuthorizedContext> contextos = new ArrayList<>();

		for (Membership membership : membershipRepository.findAllByAccountIdAndActiveTrue(accountId)) {
			if (!membership.isValidAt(ahora)) {
				continue;
			}
			Optional<Organization> organization =
					organizationRepository.findByIdAndActiveTrue(membership.getOrganizationId());
			if (organization.isEmpty() || !esSeleccionable(membership.getOrganizationId())) {
				continue;
			}
			agregarSedes(contextos, membership, organization.get());
		}
		return List.copyOf(contextos);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasActiveMembership(long accountId, long organizationId) {
		return membershipVigente(accountId, organizationId).isPresent();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Cuatro condiciones, todas necesarias: membership vigente en esa organizacion, alcance
	 * de la membership compatible con la sede pedida, consultorio activo Y de esa organizacion,
	 * y organizacion vigente con suscripcion no cancelada. Que falle cualquiera devuelve lo
	 * mismo: el llamador no puede deducir cual fallo, y por lo tanto no puede usar este metodo
	 * para averiguar si una organizacion ajena existe.
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean isContextAuthorized(long accountId, long organizationId, long consultorioId) {
		Optional<Membership> membership = membershipVigente(accountId, organizationId);
		if (membership.isEmpty()) {
			return false;
		}
		Long alcance = membership.get().getConsultorioId();
		if (alcance != null && alcance != consultorioId) {
			// Membership acotada a otra sede. El alcance null significa "toda la organizacion".
			return false;
		}
		return consultorioRepository
				.findByIdAndOrganizationIdAndActiveTrue(consultorioId, organizationId)
				.isPresent();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ActiveContext> activeContext(long accountId) {
		return activeContextRepository.findByAccountId(accountId)
				.filter(puntero -> isContextAuthorized(
						accountId, puntero.getOrganizationId(), puntero.getConsultorioId()))
				.map(puntero -> new ActiveContext(
						puntero.getOrganizationId(), puntero.getConsultorioId()));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Valida antes de escribir: si el contexto no esta autorizado, el puntero anterior queda
	 * intacto. Guardar primero y validar despues dejaria a la cuenta apuntando a un contexto al
	 * que no puede entrar.
	 *
	 * <p>El evento de auditoria solo se emite si el puntero efectivamente cambio. Reseleccionar
	 * el mismo contexto es un no-op —el frontend lo hace en cada arranque— y auditarlo llenaria
	 * el historial de ruido, que es la forma mas eficaz de volver inutil una auditoria.
	 */
	@Override
	@Transactional
	public ActiveContext selectContext(long accountId, long organizationId, long consultorioId) {
		if (!isContextAuthorized(accountId, organizationId, consultorioId)) {
			log.info("Seleccion de contexto rechazada: accountId={} organizationId={}",
					accountId, organizationId);
			throw new ContextNotAuthorizedException(accountId, organizationId, consultorioId);
		}

		Optional<AccountActiveContext> existente =
				activeContextRepository.findByAccountId(accountId);

		AccountActiveContext puntero;
		boolean cambio;
		Long organizacionAnterior = null;
		Long consultorioAnterior = null;

		if (existente.isPresent()) {
			puntero = existente.get();
			organizacionAnterior = puntero.getOrganizationId();
			consultorioAnterior = puntero.getConsultorioId();
			cambio = puntero.pointTo(organizationId, consultorioId);
		} else {
			puntero = new AccountActiveContext(accountId, organizationId, consultorioId);
			cambio = true;
		}
		activeContextRepository.save(puntero);

		if (cambio) {
			Map<String, String> details = new LinkedHashMap<>();
			details.put("toOrganizationId", String.valueOf(organizationId));
			details.put("toConsultorioId", String.valueOf(consultorioId));
			if (organizacionAnterior != null) {
				details.put("fromOrganizationId", String.valueOf(organizacionAnterior));
				details.put("fromConsultorioId", String.valueOf(consultorioAnterior));
			}
			auditTrail.record(new AuditEntry(
					organizationId,
					consultorioId,
					accountId,
					AuditEvents.CONTEXT_SELECTED,
					AuditEvents.ENTITY_ACTIVE_CONTEXT,
					puntero.getId(),
					organizacionAnterior == null ? null : String.valueOf(organizacionAnterior),
					String.valueOf(organizationId),
					details,
					null,
					AuditEvents.correlationId(),
					Instant.now()));
		}

		return new ActiveContext(organizationId, consultorioId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MembershipSnapshot> membership(long accountId, long organizationId) {
		return membershipRepository
				.findByOrganizationIdAndAccountIdAndActiveTrue(organizationId, accountId)
				.map(this::toSnapshot);
	}

	/** Membership vigente de la cuenta en el tenant, si la organizacion sigue siendo usable. */
	private Optional<Membership> membershipVigente(long accountId, long organizationId) {
		Optional<Membership> membership = membershipRepository
				.findByOrganizationIdAndAccountIdAndActiveTrue(organizationId, accountId)
				.filter(m -> m.isValidAt(Instant.now()));
		if (membership.isEmpty()) {
			return Optional.empty();
		}
		if (organizationRepository.findByIdAndActiveTrue(organizationId).isEmpty()
				|| !esSeleccionable(organizationId)) {
			return Optional.empty();
		}
		return membership;
	}

	/**
	 * Indica si el tenant admite que alguien entre a trabajar.
	 *
	 * <p>CANCELADA es terminal: los datos quedan integros y consultables por la plataforma,
	 * pero el contexto deja de poder seleccionarse. SUSPENDIDA si se puede seleccionar —el
	 * modo restringido permite lecturas y administracion— porque bloquear la entrada dejaria al
	 * cliente sin forma de ver su propia informacion ni de regularizar.
	 */
	private boolean esSeleccionable(long organizationId) {
		return subscriptionRepository.findByOrganizationId(organizationId)
				.map(s -> s.isActive() && s.getStatus() != SubscriptionStatus.CANCELADA)
				.orElse(false);
	}

	private void agregarSedes(
			List<AuthorizedContext> destino, Membership membership, Organization organization) {

		if (membership.getConsultorioId() == null) {
			// Alcance ORGANIZACION: la membership habilita todas las sedes activas.
			for (Consultorio consultorio : consultorioRepository
					.findAllByOrganizationIdAndActiveTrue(organization.getId())) {
				destino.add(toContext(organization, consultorio));
			}
			return;
		}
		consultorioRepository
				.findByIdAndOrganizationIdAndActiveTrue(
						membership.getConsultorioId(), organization.getId())
				.ifPresent(consultorio -> destino.add(toContext(organization, consultorio)));
	}

	private AuthorizedContext toContext(Organization organization, Consultorio consultorio) {
		return new AuthorizedContext(
				organization.getId(), organization.getName(),
				consultorio.getId(), consultorio.getName());
	}

	private MembershipSnapshot toSnapshot(Membership membership) {
		return new MembershipSnapshot(
				membership.getId(),
				membership.getRoleCode().name(),
				membership.isFounder(),
				membership.getValidFrom(),
				membership.getValidUntil(),
				membership.isActive());
	}
}
