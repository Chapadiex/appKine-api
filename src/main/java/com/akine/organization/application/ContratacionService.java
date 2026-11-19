package com.akine.organization.application;

import com.akine.organization.domain.Plan;
import com.akine.organization.domain.SituacionDeCobro;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.ContratacionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.PruebasGratis;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contratacion de un centro: plan, servicios y adicionales (docs/diseno/AKINE-landing-y-ofertas.md).
 *
 * <p>Lo que hace cumplir cada parte vive en otro lado: el tope de sedes en {@link PlanGateService},
 * los servicios en {@code ServicioContratadoInterceptor} y la IA en {@link FuncionesIaService}.
 * Este servicio guarda y responde.
 */
@Service
public class ContratacionService implements PruebasGratis {

	private final ContratacionRepositoryPort contratacionRepository;
	private final SubscriptionRepositoryPort subscriptionRepository;
	private final SubscriptionTransitionRepositoryPort transitionRepository;
	private final PlanCatalogService planCatalogService;
	private final AuditTrail auditTrail;

	public ContratacionService(
			ContratacionRepositoryPort contratacionRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			SubscriptionTransitionRepositoryPort transitionRepository,
			PlanCatalogService planCatalogService,
			AuditTrail auditTrail) {
		this.contratacionRepository = contratacionRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.transitionRepository = transitionRepository;
		this.planCatalogService = planCatalogService;
		this.auditTrail = auditTrail;
	}

	@Transactional(readOnly = true)
	public ContratacionView ver(long organizationId) {
		Subscription subscription = subscriptionRepository.findByOrganizationId(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		return vista(organizationId, subscription);
	}

	@Transactional(readOnly = true)
	public boolean tieneServicio(long organizationId, Servicio servicio) {
		return contratacionRepository.serviciosDe(organizationId).contains(servicio);
	}

	/** De los centros dados, los que tienen la IA (una consulta para la pagina del listado). */
	@Transactional(readOnly = true)
	public Set<Long> conIa(java.util.Collection<Long> organizationIds) {
		return contratacionRepository.conAdicional(organizationIds, Adicional.IA);
	}

	@Transactional(readOnly = true)
	public boolean tieneAdicional(long organizationId, Adicional adicional) {
		return contratacionRepository.adicionalesDe(organizationId).contains(adicional);
	}

	/**
	 * Lo que recibe un centro creado por un camino que no elige contratacion (el autoregistro,
	 * el alta por API de una organizacion): todo, para no quitarle nada.
	 */
	@Transactional
	public void asignarCompleta(long organizationId) {
		contratacionRepository.reemplazar(organizationId,
				EnumSet.allOf(Servicio.class), EnumSet.allOf(Adicional.class));
	}

	/** Contratacion elegida por la plataforma al dar de alta un centro. */
	@Transactional
	public void asignar(long organizationId, Set<Servicio> servicios, Set<Adicional> adicionales) {
		exigirServicios(servicios);
		contratacionRepository.reemplazar(organizationId, servicios, adicionales);
	}

	/**
	 * Cambio de contratacion desde la plataforma: plan, servicios, adicionales y, con
	 * {@code contratar}, el paso de la prueba a ACTIVA. Bloquea la suscripcion como el resto de
	 * las escrituras que compiten con el gate de limites.
	 */
	@Transactional
	public ContratacionView cambiar(long organizationId, String planCode, Set<Servicio> servicios,
			Set<Adicional> adicionales, boolean contratar, long versionEsperada, Long actorAccountId) {
		exigirServicios(servicios);
		Subscription subscription = subscriptionRepository.findByOrganizationIdForUpdate(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		if (subscription.getVersion() != versionEsperada) {
			throw new OptimisticLockingFailureException("La contratacion fue modificada por otra operacion");
		}
		Plan plan = planCatalogService.requireOfrecido(planCode);
		Instant ahora = Instant.now();
		SubscriptionStatus estadoAntes = subscription.getStatus();
		Long planAntes = subscription.getPlanId();

		if (!plan.getId().equals(planAntes)) {
			subscription.changePlan(plan.getId());
		}
		if (contratar && estadoAntes == SubscriptionStatus.PRUEBA) {
			subscription.transitionTo(SubscriptionStatus.ACTIVA);
		}
		Subscription guardada = subscriptionRepository.save(subscription);
		if (estadoAntes != guardada.getStatus() || !plan.getId().equals(planAntes)) {
			transitionRepository.save(new SubscriptionTransition(
					organizationId, guardada.getId(), estadoAntes, guardada.getStatus(),
					planAntes, plan.getId(), "Cambio de contratacion desde la plataforma",
					actorAccountId, ahora));
		}
		contratacionRepository.reemplazar(organizationId, servicios, adicionales);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("plan", plan.getCode());
		detalles.put("servicios", nombres(servicios));
		detalles.put("adicionales", nombres(adicionales));
		detalles.put("estado", guardada.getStatus().name());
		auditTrail.record(new AuditEntry(
				organizationId, null, actorAccountId, AuditEvents.CONTRATACION_UPDATED,
				AuditEvents.ENTITY_SUBSCRIPTION, guardada.getId(), estadoAntes.name(),
				guardada.getStatus().name(), detalles, null, AuditEvents.correlationId(), ahora));
		return vista(organizationId, guardada);
	}

	private ContratacionView vista(long organizationId, Subscription subscription) {
		Plan plan = planCatalogService.requireById(subscription.getPlanId());
		Integer maxSedes = planCatalogService.limitsOf(plan.getId()).stream()
				.filter(l -> l.code() == LimitCode.MAX_CONSULTORIOS)
				.findFirst()
				.map(PlanLimitView::value)
				.orElse(null);
		Instant ahora = Instant.now();
		boolean soloLectura = subscription.estadoEfectivo(ahora) != SubscriptionStatus.ACTIVA;
		Instant pagadoHasta = subscription.getPagadoHasta();
		return new ContratacionView(
				organizationId, plan.getCode(), plan.getName(), maxSedes,
				contratacionRepository.serviciosDe(organizationId),
				contratacionRepository.adicionalesDe(organizationId),
				subscription.getStatus(), subscription.getPruebaHasta(), soloLectura,
				contratacionRepository.definida(organizationId), subscription.getVersion(),
				pagadoHasta, SituacionDeCobro.de(pagadoHasta, ahora), SituacionDeCobro.cierreEl(pagadoHasta));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<PagoQueVence> pagosQueVencenEntre(Instant desde, Instant hasta) {
		return contratacionRepository.pagosQueVencenEntre(desde, hasta);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<PruebaEnCurso> queTerminanEntre(Instant desde, Instant hasta) {
		return contratacionRepository.pruebasQueTerminanEntre(desde, hasta);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<Long> administradores(long organizationId) {
		return contratacionRepository.administradores(organizationId);
	}

	private static void exigirServicios(Set<Servicio> servicios) {
		if (servicios == null || servicios.isEmpty()) {
			throw new IllegalArgumentException("Elegí al menos un servicio");
		}
	}

	private static String nombres(Set<? extends Enum<?>> valores) {
		return valores.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
	}
}
