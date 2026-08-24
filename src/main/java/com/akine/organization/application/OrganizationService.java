package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.OperationalStatus;
import com.akine.organization.domain.Plan;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.OrganizationSlugTakenException;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alta, edicion y lectura del tenant (RF-M01-001).
 *
 * <p>La organizacion no tiene maquina de estados propia: solo baja logica. Su estado operativo
 * DERIVA de la suscripcion. Duplicarlo habilitaria la contradiccion "organizacion activa con
 * suscripcion cancelada", que nadie sabria resolver.
 *
 * <p>Toda operacion sensible audita DENTRO de la transaccion (T-2): si el registro de
 * auditoria falla, la operacion no se confirma. No es un listener post-commit y no debe
 * convertirse en uno — un listener que falla despues del commit deja la mutacion hecha y sin
 * rastro.
 */
@Service
public class OrganizationService {

	private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

	/** Zona por defecto del tenant. Las reglas locales no se pueden calcular sobre UTC. */
	static final String TIMEZONE_POR_DEFECTO = "America/Argentina/Cordoba";

	private final OrganizationRepositoryPort organizationRepository;
	private final ConsultorioRepositoryPort consultorioRepository;
	private final SubscriptionRepositoryPort subscriptionRepository;
	private final SubscriptionTransitionRepositoryPort transitionRepository;
	private final PlanCatalogService planCatalogService;
	private final AuditTrail auditTrail;

	public OrganizationService(
			OrganizationRepositoryPort organizationRepository,
			ConsultorioRepositoryPort consultorioRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			SubscriptionTransitionRepositoryPort transitionRepository,
			PlanCatalogService planCatalogService,
			AuditTrail auditTrail) {
		this.organizationRepository = organizationRepository;
		this.consultorioRepository = consultorioRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.transitionRepository = transitionRepository;
		this.planCatalogService = planCatalogService;
		this.auditTrail = auditTrail;
	}

	/**
	 * Alta administrativa de un tenant, sin propietario.
	 *
	 * <p>Es el camino de ops y QA. El alta self-service NO pasa por aca sino por
	 * {@code InitialOrganizationProvisioning}, que ademas crea la cuenta y la membership del
	 * fundador en la misma transaccion (ADR-0008).
	 *
	 * <p>No hay registro de idempotencia en este camino: la tabla
	 * {@code organization_onboarding} exige {@code account_id} y {@code membership_id} NOT
	 * NULL, y aca no hay propietario que registrar. Quien cierra el alta duplicada es
	 * {@code uk_organization_slug}: reintentar con el mismo slug da un conflicto explicito, que
	 * para esta operacion es una garantia equivalente y mas simple.
	 *
	 * @param slug identificador legible. {@code null} lo deriva del nombre
	 */
	@Transactional
	public OrganizationView create(
			String name, String slug, String timezone, String planCode, Long actorAccountId) {
		Plan plan = planCatalogService.requireContractable(planCode);
		ProvisionedTenant tenant = provisionTenant(name, slug, timezone, plan, name, actorAccountId);
		return toView(tenant.organization(), tenant.subscription());
	}

	/**
	 * Crea organizacion + primer consultorio + suscripcion + fila inicial del historico.
	 *
	 * <p>Package-private: lo comparten el alta administrativa y el onboarding compuesto, que
	 * le agrega la membership del fundador y el registro de idempotencia. Tenerlo en un solo
	 * lugar evita que los dos caminos creen tenants sutilmente distintos.
	 *
	 * <p>El primer consultorio se crea SIEMPRE, incluso en el alta administrativa: el contexto
	 * de trabajo es Organizacion + Consultorio (ADR-0009), asi que un tenant sin ninguna sede
	 * no ofrece ningun contexto seleccionable y quien entrara despues no tendria a donde.
	 */
	ProvisionedTenant provisionTenant(
			String name, String slug, String timezone, Plan plan,
			String consultorioName, Long actorAccountId) {

		String slugFinal = slug == null || slug.isBlank()
				? Slugs.derivar(name, organizationRepository::existsBySlug)
				: Slugs.normalizar(slug);
		String zona = timezone == null || timezone.isBlank() ? TIMEZONE_POR_DEFECTO : timezone;
		Instant ahora = Instant.now();

		Organization organization;
		try {
			organization = organizationRepository.save(new Organization(name, slugFinal, zona));
		} catch (DataIntegrityViolationException colision) {
			// uk_organization_slug. Se traduce ACA, donde se sabe cual es el unique que choco:
			// mas abajo, dentro del alta compuesta, hay OTRA violacion posible
			// —uk_onboarding_key— y son dos causas con dos respuestas distintas. Tratarlas
			// juntas, como se hacia antes, respondia mal las dos: al cliente que eligio un slug
			// tomado le llegaba el desenlace de una carrera, y a la carrera le llegaba un 500.
			//
			// El chequeo previo de Slugs.derivar y de requireSlugDisponible no reemplaza a esta
			// traduccion: entre el SELECT y el INSERT hay una ventana y quien decide de verdad
			// es la restriccion.
			log.info("Slug de organizacion ya tomado: slug={}", slugFinal);
			throw new OrganizationSlugTakenException(slugFinal);
		}
		// La sede nace con la zona de la organizacion. Desde 02.01 la zona vive en el
		// consultorio y la de la organizacion es solo el valor que se propone: copiarla aca es
		// lo que hace que el backfill de V17 sea exacto y no una aproximacion.
		Consultorio consultorio = consultorioRepository.save(
				new Consultorio(organization.getId(), consultorioName, zona, null));
		Subscription subscription = subscriptionRepository.save(
				new Subscription(organization.getId(), plan.getId(), ahora));

		// Fila inicial del historico: from = null significa "alta de la suscripcion". Sin ella,
		// el historico de un tenant empezaria en su primera suspension y no habria forma de
		// saber con que plan nacio.
		transitionRepository.save(new SubscriptionTransition(
				organization.getId(),
				subscription.getId(),
				null,
				subscription.getStatus(),
				null,
				plan.getId(),
				null,
				actorAccountId,
				ahora));

		Map<String, String> details = new LinkedHashMap<>();
		details.put("slug", slugFinal);
		details.put("planCode", plan.getCode());
		details.put("timezone", zona);
		auditTrail.record(new AuditEntry(
				organization.getId(),
				consultorio.getId(),
				actorAccountId,
				AuditEvents.ORGANIZATION_CREATED,
				AuditEvents.ENTITY_ORGANIZATION,
				organization.getId(),
				null,
				subscription.getStatus().name(),
				details,
				null,
				AuditEvents.correlationId(),
				ahora));

		log.info("Organizacion creada: organizationId={} slug={} planCode={}",
				organization.getId(), slugFinal, plan.getCode());

		return new ProvisionedTenant(organization, consultorio, subscription);
	}

	/**
	 * Edita los datos mutables del tenant.
	 *
	 * <p>El slug no se toca: se usa en URLs y en soporte, y renombrarlo rompe enlaces. Cambiar
	 * de identificador es crear otro tenant, no editar este.
	 *
	 * <p>{@code expectedVersion} es obligatorio y se compara ANTES de mutar: sin el, dos
	 * ediciones concurrentes se pisan y el segundo en guardar borra el cambio del primero sin
	 * que nadie se entere. El {@code @Version} de la entity cubre la ventana que queda entre
	 * esta comparacion y el commit.
	 *
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409)
	 */
	@Transactional
	public OrganizationView update(
			long organizationId, String name, String timezone,
			long expectedVersion, Long actorAccountId) {

		Organization organization = requireActive(organizationId);
		if (organization.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La organizacion fue modificada por otra operacion");
		}

		Map<String, String> details = new LinkedHashMap<>();
		String nombreFinal = name == null || name.isBlank() ? organization.getName() : name;
		String zonaFinal = timezone == null || timezone.isBlank()
				? organization.getTimezone() : timezone;

		if (!nombreFinal.equals(organization.getName())) {
			details.put("name", organization.getName() + " -> " + nombreFinal);
		}
		if (!zonaFinal.equals(organization.getTimezone())) {
			details.put("timezone", organization.getTimezone() + " -> " + zonaFinal);
		}

		organization.update(nombreFinal, zonaFinal);
		organizationRepository.save(organization);

		// Se audita aunque no haya cambiado nada: el intento de edicion tambien es un hecho, y
		// omitirlo dejaria un hueco en el historial justo cuando alguien lo revisa.
		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				actorAccountId,
				AuditEvents.ORGANIZATION_UPDATED,
				AuditEvents.ENTITY_ORGANIZATION,
				organizationId,
				null,
				null,
				details,
				null,
				AuditEvents.correlationId(),
				Instant.now()));

		return toView(organization, subscriptionOf(organizationId));
	}

	/**
	 * Lectura del tenant.
	 *
	 * @throws OrganizationNotFoundException si no existe, esta dada de baja o es de otro
	 *         tenant. Los tres casos se responden igual: distinguirlos permitiria enumerar
	 *         clientes del SaaS probando ids
	 */
	@Transactional(readOnly = true)
	public OrganizationView find(long organizationId) {
		Organization organization = requireActive(organizationId);
		return toView(organization, subscriptionOf(organizationId));
	}

	/**
	 * Sedes del tenant, filtradas por estado (RF-M03-001, RNF-M03-004).
	 *
	 * <p><b>Sin permiso propio, y tiene que seguir asi.</b> La autorizacion de este listado es
	 * "miembro vigente del tenant" desde 01.01, porque es el insumo del selector de contexto de
	 * trabajo: restringirlo a {@code consultorio:manage} dejaria a un {@code PROFESIONAL} sin
	 * poder elegir sede, o sea sin salida despues del login. La matriz no tiene un
	 * {@code consultorio:read}, asi que queda declarado como excepcion al catalogo (D-10,
	 * abierta y compartida con la D-9 de 01.03).
	 *
	 * <p>El default {@code ACTIVO} conserva el comportamiento que el endpoint ya tenia: un
	 * cliente viejo no ve ninguna diferencia, y por eso el cambio de contrato es aditivo.
	 */
	@Transactional(readOnly = true)
	public List<ConsultorioView> consultorios(long organizationId, ConsultorioEstadoFiltro estado) {
		requireActive(organizationId);
		List<Consultorio> sedes = estado == ConsultorioEstadoFiltro.ACTIVO
				? consultorioRepository.findAllByOrganizationIdAndActiveTrue(organizationId)
				: consultorioRepository.findAllByOrganizationId(organizationId);
		return sedes.stream()
				.filter(c -> estado != ConsultorioEstadoFiltro.INACTIVO || !c.isActive())
				.map(ConsultorioView::de)
				.toList();
	}

	/**
	 * Estado operativo efectivo del tenant.
	 *
	 * <p>Valor calculado, nunca una columna: la baja logica de la organizacion gana sobre
	 * cualquier estado de la suscripcion.
	 */
	@Transactional(readOnly = true)
	public OperationalStatus operationalStatus(long organizationId) {
		Organization organization = organizationRepository.findById(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		return subscriptionOf(organizationId).operationalStatus(organization.isActive());
	}

	/**
	 * Exige que un slug explicito este disponible, sin escribir nada.
	 *
	 * <p>Solo mira los slugs que manda el llamador: cuando se deriva del nombre, la colision ya
	 * la resuelve {@link Slugs#derivar} con un sufijo numerico y rechazar ahi seria inventar un
	 * error donde hay una solucion.
	 *
	 * <p>Es un chequeo PREVIO, no una garantia: entre este SELECT y el INSERT hay una ventana y
	 * quien cierra la carrera sigue siendo {@code uk_organization_slug}. Existe para que el
	 * registro self-service pueda validarlo <b>antes</b> de mirar si el email tiene cuenta, que
	 * es lo que hace que la respuesta sea la misma exista o no la cuenta (ADR-0018).
	 *
	 * @throws OrganizationSlugTakenException si el slug ya lo usa otro tenant (409)
	 */
	@Transactional(readOnly = true)
	public void requireSlugDisponible(String slug) {
		if (slug == null || slug.isBlank()) {
			return;
		}
		String normalizado = Slugs.normalizar(slug);
		if (organizationRepository.existsBySlug(normalizado)) {
			log.info("Slug de organizacion ya tomado: slug={}", normalizado);
			throw new OrganizationSlugTakenException(normalizado);
		}
	}

	/** Organizacion vigente, o el mismo 404 que si no existiera. */
	Organization requireActive(long organizationId) {
		return organizationRepository.findByIdAndActiveTrue(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	private Subscription subscriptionOf(long organizationId) {
		return subscriptionRepository.findByOrganizationId(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	private OrganizationView toView(Organization organization, Subscription subscription) {
		return new OrganizationView(
				organization.getId(),
				organization.getName(),
				organization.getSlug(),
				organization.getTimezone(),
				organization.isActive(),
				organization.getVersion(),
				subscription.operationalStatus(organization.isActive()));
	}
}
