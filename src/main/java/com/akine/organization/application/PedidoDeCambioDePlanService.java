package com.akine.organization.application;

import com.akine.organization.domain.Organization;
import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.organization.domain.exception.PedidoDeCambioDePlanNotFoundException;
import com.akine.organization.domain.exception.PedidoDePlanPendienteException;
import com.akine.organization.domain.exception.PedidoExcedeLimitesException;
import com.akine.organization.domain.exception.PedidoExcedeLimitesException.Exceso;
import com.akine.organization.domain.exception.PedidoSinCambiosException;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.PedidoDeCambioDePlanRepositoryPort;
import com.akine.organization.domain.Plan;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pedidos de cambio de plan (25/11/2026, docs/diseno/AKINE-pedido-de-cambio-de-plan.md).
 *
 * <p>El centro pide desde Suscripcion; la plataforma aprueba —se aplica con
 * {@link ContratacionService#cambiar}, el mismo camino que usa a mano— o rechaza con motivo. Un
 * solo pedido pendiente por centro, garantizado por el indice parcial de V50.
 */
@Service
public class PedidoDeCambioDePlanService {

	private final PedidoDeCambioDePlanRepositoryPort repository;
	private final ContratacionService contratacionService;
	private final PlanCatalogService planCatalogService;
	private final OrganizationRepositoryPort organizationRepository;
	private final TenantUsageCounter usageCounter;
	private final AuditTrail auditTrail;

	/** Los topes que se controlan al bajar de plan: lo que el centro tiene que cerrar o dar de baja. */
	private static final List<LimitCode> TOPES_A_CONTROLAR =
			List.of(LimitCode.MAX_CONSULTORIOS, LimitCode.MAX_PROFESIONALES);

	public PedidoDeCambioDePlanService(PedidoDeCambioDePlanRepositoryPort repository,
			ContratacionService contratacionService, PlanCatalogService planCatalogService,
			OrganizationRepositoryPort organizationRepository, TenantUsageCounter usageCounter,
			AuditTrail auditTrail) {
		this.repository = repository;
		this.contratacionService = contratacionService;
		this.planCatalogService = planCatalogService;
		this.organizationRepository = organizationRepository;
		this.usageCounter = usageCounter;
		this.auditTrail = auditTrail;
	}

	/**
	 * Guarda el pedido del centro. 404 si el plan no se ofrece; 400 si es igual a lo contratado;
	 * 409 si ya hay uno pendiente (tambien cuando dos llegan a la vez: lo resuelve el indice).
	 */
	@Transactional
	public PedidoDeCambioDePlanView pedir(long organizationId, String planCode, Set<Servicio> servicios,
			Set<Adicional> adicionales, String comentario, long actorAccountId) {
		if (servicios == null || servicios.isEmpty()) {
			throw new IllegalArgumentException("Elegí al menos un servicio");
		}
		planCatalogService.requireOfrecido(planCode);
		ContratacionView actual = contratacionService.ver(organizationId);
		if (actual.planCode().equals(planCode) && actual.servicios().equals(servicios)
				&& actual.adicionales().equals(adicionales)) {
			throw new PedidoSinCambiosException();
		}
		repository.findFirstByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId)
				.filter(p -> p.getEstado() == PedidoDeCambioDePlan.Estado.PENDIENTE)
				.ifPresent(p -> {
					throw new PedidoDePlanPendienteException();
				});
		PedidoDeCambioDePlan guardado;
		try {
			guardado = repository.saveAndFlush(new PedidoDeCambioDePlan(organizationId, planCode, servicios,
					adicionales, actual.planCode(), actual.servicios(), actual.adicionales(),
					limpio(comentario), actorAccountId));
		} catch (DataIntegrityViolationException carrera) {
			// ux_pedido_de_cambio_de_plan_pendiente: otro pedido del mismo centro entro primero.
			throw new PedidoDePlanPendienteException();
		}
		auditar(guardado, AuditEvents.PEDIDO_PLAN_RECIBIDO, null, actorAccountId);
		return vista(guardado);
	}

	/** El ultimo pedido del centro, en cualquier estado. */
	@Transactional(readOnly = true)
	public Optional<PedidoDeCambioDePlanView> ultimo(long organizationId) {
		return repository.findFirstByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId).map(this::vista);
	}

	/** El centro desiste mientras espera. Pedido de otro centro: 404. */
	@Transactional
	public PedidoDeCambioDePlanView cancelar(long organizationId, long id, long versionEsperada,
			long actorAccountId) {
		PedidoDeCambioDePlan pedido = pendiente(id, versionEsperada);
		if (pedido.getOrganizationId() != organizationId) {
			throw new PedidoDeCambioDePlanNotFoundException(id);
		}
		pedido.cancelar(actorAccountId, Instant.now());
		return cerrar(pedido, actorAccountId);
	}

	@Transactional(readOnly = true)
	public Page<PedidoDeCambioDePlanView> listar(PedidoDeCambioDePlan.Estado estado, String q, Pageable pageable) {
		Page<PedidoDeCambioDePlan> pagina = repository.buscar(estado, q, pageable);
		Map<Long, String> centros = nombresDeCentros(pagina.map(PedidoDeCambioDePlan::getOrganizationId).toList());
		Map<String, String> planes = nombresDePlanes();
		return pagina.map(p -> vista(p, centros, planes));
	}

	@Transactional(readOnly = true)
	public long pendientes() {
		return repository.countByEstado(PedidoDeCambioDePlan.Estado.PENDIENTE);
	}

	/**
	 * Aplica lo pedido con {@link ContratacionService#cambiar}, en la misma transaccion. No pasa una
	 * prueba a ACTIVA: eso sigue siendo un acto aparte de la plataforma.
	 */
	@Transactional
	public PedidoDeCambioDePlanView aprobar(long id, long versionEsperada, long actorAccountId) {
		PedidoDeCambioDePlan pedido = pendiente(id, versionEsperada);
		// Bajar de plan exige ajustarse antes: sedes y profesionales dentro del tope nuevo (26/11/2026).
		List<Exceso> excesos = excesos(pedido);
		if (!excesos.isEmpty()) {
			throw new PedidoExcedeLimitesException(excesos);
		}
		long versionDeLaSuscripcion = contratacionService.ver(pedido.getOrganizationId()).version();
		contratacionService.cambiar(pedido.getOrganizationId(), pedido.getPlanCode(), pedido.getServicios(),
				pedido.getAdicionales(), false, versionDeLaSuscripcion, actorAccountId);
		pedido.aprobar(actorAccountId, Instant.now());
		return cerrar(pedido, actorAccountId);
	}

	@Transactional
	public PedidoDeCambioDePlanView rechazar(long id, String motivo, long versionEsperada, long actorAccountId) {
		PedidoDeCambioDePlan pedido = pendiente(id, versionEsperada);
		pedido.rechazar(motivo, actorAccountId, Instant.now());
		return cerrar(pedido, actorAccountId);
	}

	/** 404 si no existe; 409 si ya se resolvio o la version quedo vieja. */
	private PedidoDeCambioDePlan pendiente(long id, long versionEsperada) {
		PedidoDeCambioDePlan pedido = repository.findById(id)
				.orElseThrow(() -> new PedidoDeCambioDePlanNotFoundException(id));
		if (pedido.getEstado() != PedidoDeCambioDePlan.Estado.PENDIENTE || pedido.getVersion() != versionEsperada) {
			throw new OptimisticLockingFailureException("El pedido cambio mientras lo mirabas");
		}
		return pedido;
	}

	private PedidoDeCambioDePlanView cerrar(PedidoDeCambioDePlan pedido, long actorAccountId) {
		PedidoDeCambioDePlan guardado = repository.saveAndFlush(pedido);
		auditar(guardado, AuditEvents.PEDIDO_PLAN_RESUELTO, PedidoDeCambioDePlan.Estado.PENDIENTE.name(),
				actorAccountId);
		return vista(guardado);
	}

	private void auditar(PedidoDeCambioDePlan pedido, String evento, String antes, long actorAccountId) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("plan", pedido.getPlanCodeAnterior() + " -> " + pedido.getPlanCode());
		detalles.put("servicios", nombres(pedido.getServicios()));
		detalles.put("adicionales", nombres(pedido.getAdicionales()));
		if (pedido.getMotivoRechazo() != null) {
			detalles.put("motivo", pedido.getMotivoRechazo());
		}
		auditTrail.record(new AuditEntry(pedido.getOrganizationId(), null, actorAccountId, evento,
				AuditEvents.ENTITY_PEDIDO_PLAN, pedido.getId(), antes, pedido.getEstado().name(), detalles,
				null, AuditEvents.correlationId(), Instant.now()));
	}

	private PedidoDeCambioDePlanView vista(PedidoDeCambioDePlan pedido) {
		return vista(pedido, nombresDeCentros(List.of(pedido.getOrganizationId())), nombresDePlanes());
	}

	private PedidoDeCambioDePlanView vista(PedidoDeCambioDePlan p, Map<Long, String> centros,
			Map<String, String> planes) {
		List<Exceso> excesos = p.getEstado() == PedidoDeCambioDePlan.Estado.PENDIENTE ? excesos(p) : List.of();
		return PedidoDeCambioDePlanView.de(p, centros.get(p.getOrganizationId()),
				planes.getOrDefault(p.getPlanCode(), p.getPlanCode()),
				planes.getOrDefault(p.getPlanCodeAnterior(), p.getPlanCodeAnterior()), excesos);
	}

	/**
	 * Los topes del plan pedido que el centro hoy supera. Un plan que ya no se ofrece no se controla
	 * aca: aprobar lo rechaza igual con 404.
	 */
	private List<Exceso> excesos(PedidoDeCambioDePlan pedido) {
		Plan plan;
		try {
			plan = planCatalogService.requireOfrecido(pedido.getPlanCode());
		} catch (PlanNotFoundException retirado) {
			return List.of();
		}
		List<PlanLimitView> topes = planCatalogService.limitsOf(plan.getId());
		return TOPES_A_CONTROLAR.stream()
				.flatMap(codigo -> topes.stream().filter(t -> t.code() == codigo && t.value() != null))
				.map(t -> new Exceso(t.code(), t.value(), usageCounter.count(t.code(), pedido.getOrganizationId())))
				.filter(e -> e.uso() > e.tope())
				.toList();
	}

	private Map<Long, String> nombresDeCentros(Collection<Long> ids) {
		return organizationRepository.findAllById(Set.copyOf(ids)).stream()
				.collect(Collectors.toMap(Organization::getId, Organization::getName, (a, b) -> a));
	}

	private Map<String, String> nombresDePlanes() {
		return planCatalogService.catalog().stream()
				.collect(Collectors.toMap(PlanView::code, PlanView::name, (a, b) -> a));
	}

	private static String nombres(Set<? extends Enum<?>> valores) {
		return valores.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
	}

	private static String limpio(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

}
