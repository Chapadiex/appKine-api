package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.FinanciadorCatalogo;
import com.akine.contracting.domain.PlanCatalogo;
import com.akine.contracting.domain.exception.FinanciadorCodigoTakenException;
import com.akine.contracting.domain.exception.FinanciadorCuitTakenException;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNombreTakenException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.FinanciadorYaInactivoException;
import com.akine.contracting.domain.exception.PlanCodigoTakenException;
import com.akine.contracting.domain.exception.PlanNombreTakenException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorCatalogoRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCatalogoRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * El catalogo GLOBAL de financiadores y planes de la plataforma (M15, DP-43).
 *
 * <h2>Quien hace que</h2>
 *
 * <pre>
 *   PLATFORM_ADMIN   lo administra: alta, edicion y baja de financiadores y planes. Sin contexto
 *                    de tenant —el filtro no se lo publica— y sin convenio:manage sobre ningun
 *                    centro (matriz §13.2 sigue valiendo).
 *   CENTRO           lo lee con convenio:read para elegir que adoptar. Adoptar lo hace
 *                    AdopcionDeFinanciadorService con convenio:manage.
 * </pre>
 *
 * <h2>Editar el catalogo no propaga</h2>
 *
 * <p>Ninguna operacion de esta clase toca {@code financiador} ni {@code plan_cobertura}: las
 * copias son de los centros. Lo unico que el centro ve de una edicion posterior es
 * {@code actualizacionDisponible}, calculado al leer comparando la ultima edicion de la entrada
 * contra el instante en que adopto su copia. Aplicar el cambio —re-sincronizar— no existe y es
 * deliberado: la copia puede haber sido editada por el centro, y pisarla seria la "referencia"
 * que DP-43 descarto.
 */
@Service
public class CatalogoDeFinanciadoresService {

	private static final Logger log = LoggerFactory.getLogger(CatalogoDeFinanciadoresService.class);

	private final FinanciadorCatalogoRepositoryPort catalogo;
	private final PlanCatalogoRepositoryPort planesCatalogo;
	private final FinanciadorRepositoryPort financiadores;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public CatalogoDeFinanciadoresService(
			FinanciadorCatalogoRepositoryPort catalogo,
			PlanCatalogoRepositoryPort planesCatalogo,
			FinanciadorRepositoryPort financiadores,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.catalogo = catalogo;
		this.planesCatalogo = planesCatalogo;
		this.financiadores = financiadores;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/** La consola de plataforma: todo el catalogo segun el filtro, con todos los planes. */
	@Transactional(readOnly = true)
	public List<FinanciadorCatalogoView> buscarEnPlataforma(
			OperatingActor actor, FinanciadorBusqueda filtros) {

		exigirPlataforma(actor, "listar el catalogo global de financiadores");
		List<FinanciadorCatalogo> encontrados =
				catalogo.buscar(filtros.patron(), filtros.activoFiltro(), filtros.tipoFiltro());
		Map<Long, List<PlanCatalogo>> planes = planesDe(encontrados);
		return encontrados.stream()
				.map(f -> vista(f, planes.getOrDefault(f.getId(), List.of()), null, false))
				.toList();
	}

	@Transactional(readOnly = true)
	public FinanciadorCatalogoView verEnPlataforma(OperatingActor actor, long catalogoId) {
		exigirPlataforma(actor, "ver un financiador del catalogo global");
		FinanciadorCatalogo financiador = cargar(catalogoId);
		return vista(financiador, planesDe(List.of(financiador)).getOrDefault(catalogoId, List.of()),
				null, false);
	}

	/**
	 * Lo que un centro puede adoptar: los financiadores ACTIVOS del catalogo con sus planes
	 * ACTIVOS, y por cada uno la copia vigente que el centro ya adopto, si la hay.
	 *
	 * <p>{@code convenio:read}, el mismo permiso que leer el catalogo propio: elegir que adoptar es
	 * parte de administrar con quien trabaja el centro. La respuesta es la misma para todos los
	 * centros salvo las dos columnas de la adopcion, que salen de las filas del tenant del contexto.
	 */
	@Transactional(readOnly = true)
	public List<FinanciadorCatalogoView> buscarParaElCentro(
			OperatingActor actor, FinanciadorBusqueda filtros) {

		long organizationId = AutorizacionDeCatalogo.exigirLectura(
				permissionGuard, actor, "Buscar en el catalogo global de financiadores");

		List<FinanciadorCatalogo> encontrados = catalogo.buscar(filtros.patron(), 1, filtros.tipoFiltro());
		if (encontrados.isEmpty()) {
			return List.of();
		}
		Map<Long, List<PlanCatalogo>> planes = planesDe(encontrados);
		Map<Long, Financiador> adoptados = financiadores
				.findAllByOrganizationIdAndCatalogoIdInAndDeletedAtIsNull(
						organizationId, encontrados.stream().map(FinanciadorCatalogo::getId).toList())
				.stream()
				.collect(Collectors.toMap(Financiador::getCatalogoId, Function.identity()));

		return encontrados.stream()
				.map(f -> {
					List<PlanCatalogo> todos = planes.getOrDefault(f.getId(), List.of());
					Financiador copia = adoptados.get(f.getId());
					Instant actualizadoEn = actualizadoEn(f, todos);
					boolean desactualizada =
							copia != null && actualizadoEn.isAfter(copia.getCreatedAt());
					List<PlanCatalogo> activos = todos.stream().filter(PlanCatalogo::isOperable).toList();
					return vista(f, activos, actualizadoEn, copia == null ? null : copia.getId(),
							desactualizada);
				})
				.toList();
	}

	// =================================================================================
	// Mutaciones del financiador del catalogo
	// =================================================================================

	@Transactional
	public FinanciadorCatalogoView crear(OperatingActor actor, FinanciadorAltaCommand command) {
		exigirPlataforma(actor, "crear un financiador del catalogo global");

		FinanciadorCatalogo nuevo = new FinanciadorCatalogo(
				command.codigo(),
				command.nombre(),
				command.tipo(),
				command.cuit(),
				command.emailContacto(),
				command.telefonoContacto(),
				command.observaciones());

		FinanciadorCatalogo creado;
		try {
			creado = catalogo.saveAndFlush(nuevo);
		} catch (DataIntegrityViolationException choque) {
			throw traducirChoqueDeFinanciador(choque, nuevo.getCodigo(), true);
		}

		auditar(AuditEvents.FINANCIADOR_CATALOGO_CREATED, AuditEvents.ENTITY_FINANCIADOR_CATALOGO,
				creado.getId(), actor, null, "ACTIVO", null,
				Map.of("codigo", creado.getCodigo(), "tipo", creado.getTipo().name()));
		log.info("Financiador del catalogo global creado: catalogoId={} codigo={}",
				creado.getId(), creado.getCodigo());
		return vista(creado, List.of(), null, false);
	}

	@Transactional
	public FinanciadorCatalogoView editar(
			OperatingActor actor, long catalogoId, FinanciadorEdicionCommand command) {

		exigirPlataforma(actor, "editar un financiador del catalogo global");
		FinanciadorCatalogo financiador = cargar(catalogoId);
		if (!financiador.isOperable()) {
			throw new FinanciadorYaInactivoException(catalogoId, "editar");
		}
		exigirVersion(financiador.getVersion(), command.expectedVersion());

		financiador.updateDatos(
				command.nombre(),
				command.tipo(),
				command.cuit(),
				command.emailContacto(),
				command.telefonoContacto(),
				command.observaciones());

		FinanciadorCatalogo guardado;
		try {
			guardado = catalogo.saveAndFlush(financiador);
		} catch (DataIntegrityViolationException choque) {
			throw traducirChoqueDeFinanciador(choque, financiador.getCodigo(), false);
		}

		auditar(AuditEvents.FINANCIADOR_CATALOGO_UPDATED, AuditEvents.ENTITY_FINANCIADOR_CATALOGO,
				catalogoId, actor, null, null, null, Map.of());
		return vista(guardado, planesDe(List.of(guardado)).getOrDefault(catalogoId, List.of()),
				null, false);
	}

	/**
	 * Baja logica con motivo. Deja de ofrecerse para adoptar; <b>las copias ya adoptadas no se
	 * tocan</b> y sus planes del catalogo tampoco (la baja no cascadea, igual que en el centro).
	 */
	@Transactional
	public FinanciadorCatalogoView darDeBaja(OperatingActor actor, long catalogoId, String motivo) {
		exigirPlataforma(actor, "dar de baja un financiador del catalogo global");
		FinanciadorCatalogo financiador = cargar(catalogoId);
		if (!financiador.isOperable()) {
			throw new FinanciadorYaInactivoException(catalogoId, "dar de baja");
		}
		financiador.deactivate(Instant.now(), motivo);
		FinanciadorCatalogo guardado = catalogo.save(financiador);

		auditar(AuditEvents.FINANCIADOR_CATALOGO_DEACTIVATED,
				AuditEvents.ENTITY_FINANCIADOR_CATALOGO, catalogoId, actor, "ACTIVO", "INACTIVO",
				guardado.getDeactivationReason(), Map.of());
		return vista(guardado, planesDe(List.of(guardado)).getOrDefault(catalogoId, List.of()),
				null, false);
	}

	// =================================================================================
	// Mutaciones de los planes del catalogo
	// =================================================================================

	@Transactional
	public PlanCatalogoView crearPlan(
			OperatingActor actor, long catalogoId, PlanAltaCommand command) {

		exigirPlataforma(actor, "crear un plan del catalogo global");
		FinanciadorCatalogo financiador = cargar(catalogoId);
		if (!financiador.isOperable()) {
			throw new FinanciadorInactivoException(catalogoId);
		}

		PlanCatalogo nuevo = new PlanCatalogo(
				catalogoId,
				command.codigo(),
				command.nombre(),
				command.descripcion(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				// Los mismos defaults que el alta de un plan del centro: PlanCoberturaService.crear.
				Boolean.TRUE.equals(command.requiereAutorizacion()),
				command.requiereCredencial() == null || command.requiereCredencial(),
				command.copago(),
				command.moneda());

		PlanCatalogo creado;
		try {
			creado = planesCatalogo.saveAndFlush(nuevo);
		} catch (DataIntegrityViolationException choque) {
			throw ChoquesDeUnique.contiene(choque, "_nombre_")
					? new PlanNombreTakenException()
					: new PlanCodigoTakenException(nuevo.getCodigo());
		}

		auditar(AuditEvents.PLAN_CATALOGO_CREATED, AuditEvents.ENTITY_PLAN_CATALOGO, creado.getId(),
				actor, null, "ACTIVO", null,
				Map.of("codigo", creado.getCodigo(), "catalogoId", String.valueOf(catalogoId)));
		return PlanCatalogoView.de(creado);
	}

	@Transactional
	public PlanCatalogoView editarPlan(
			OperatingActor actor, long catalogoId, long planId, PlanEdicionCommand command) {

		exigirPlataforma(actor, "editar un plan del catalogo global");
		PlanCatalogo plan = cargarPlan(catalogoId, planId);
		if (!plan.isOperable()) {
			throw new PlanYaInactivoException(planId, "editar");
		}
		exigirVersion(plan.getVersion(), command.expectedVersion());

		plan.updateDatos(
				command.nombre(),
				command.descripcion(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.requiereAutorizacion(),
				command.requiereCredencial(),
				command.copago(),
				command.moneda());

		PlanCatalogo guardado;
		try {
			guardado = planesCatalogo.saveAndFlush(plan);
		} catch (DataIntegrityViolationException choque) {
			if (ChoquesDeUnique.contiene(choque, "_nombre_")) {
				throw new PlanNombreTakenException();
			}
			throw choque;
		}

		auditar(AuditEvents.PLAN_CATALOGO_UPDATED, AuditEvents.ENTITY_PLAN_CATALOGO, planId, actor,
				null, null, null, Map.of("catalogoId", String.valueOf(catalogoId)));
		return PlanCatalogoView.de(guardado);
	}

	@Transactional
	public PlanCatalogoView darDeBajaPlan(
			OperatingActor actor, long catalogoId, long planId, String motivo) {

		exigirPlataforma(actor, "dar de baja un plan del catalogo global");
		PlanCatalogo plan = cargarPlan(catalogoId, planId);
		if (!plan.isOperable()) {
			throw new PlanYaInactivoException(planId, "dar de baja");
		}
		plan.deactivate(Instant.now(), motivo);
		PlanCatalogo guardado = planesCatalogo.save(plan);

		auditar(AuditEvents.PLAN_CATALOGO_DEACTIVATED, AuditEvents.ENTITY_PLAN_CATALOGO, planId,
				actor, "ACTIVO", "INACTIVO", guardado.getDeactivationReason(),
				Map.of("catalogoId", String.valueOf(catalogoId)));
		return PlanCatalogoView.de(guardado);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/**
	 * 403 y jamas 401 ni 404: el catalogo es visible para todos los centros, asi que ocultarlo no
	 * protege nada. Mismo control que {@code ServicioService.exigirPlataforma} en M27.
	 */
	private static void exigirPlataforma(OperatingActor actor, String operacion) {
		if (!actor.platformAdmin()) {
			log.info("Operacion de plataforma sobre el catalogo de financiadores rechazada: "
					+ "accountId={} operacion={}", actor.accountId(), operacion);
			throw new AccessDeniedException(
					"El catalogo global de financiadores lo administra unicamente la plataforma");
		}
	}

	private FinanciadorCatalogo cargar(long catalogoId) {
		return catalogo.findById(catalogoId)
				.orElseThrow(() -> new FinanciadorNotAccessibleException(catalogoId));
	}

	private PlanCatalogo cargarPlan(long catalogoId, long planId) {
		return planesCatalogo.findByIdAndFinanciadorCatalogoId(planId, catalogoId)
				.orElseThrow(() -> new PlanNotAccessibleException(planId));
	}

	private static void exigirVersion(long actual, long esperada) {
		if (actual != esperada) {
			throw new OptimisticLockingFailureException(
					"La entrada del catalogo fue modificada por otra operacion");
		}
	}

	private static RuntimeException traducirChoqueDeFinanciador(
			DataIntegrityViolationException choque, String codigo, boolean alta) {

		if (ChoquesDeUnique.contiene(choque, "_nombre_")) {
			return new FinanciadorNombreTakenException();
		}
		if (ChoquesDeUnique.contiene(choque, "_cuit_")) {
			return new FinanciadorCuitTakenException();
		}
		// En una edicion el codigo no viaja: lo que no se reconoce se deja propagar, un 500
		// honesto antes que un 409 inventado (mismo criterio que FinanciadorService.editar).
		return alta ? new FinanciadorCodigoTakenException(codigo) : choque;
	}

	private Map<Long, List<PlanCatalogo>> planesDe(List<FinanciadorCatalogo> financiadoresDelCatalogo) {
		if (financiadoresDelCatalogo.isEmpty()) {
			return Map.of();
		}
		return planesCatalogo
				.findAllByFinanciadorCatalogoIdInOrderByNombreAsc(
						financiadoresDelCatalogo.stream().map(FinanciadorCatalogo::getId).toList())
				.stream()
				.collect(Collectors.groupingBy(PlanCatalogo::getFinanciadorCatalogoId));
	}

	/** La ultima edicion de la entrada: la cabecera o cualquiera de sus planes. */
	static Instant actualizadoEn(FinanciadorCatalogo financiador, List<PlanCatalogo> planes) {
		return Stream.concat(
						Stream.of(financiador.getUpdatedAt()),
						planes.stream().map(PlanCatalogo::getUpdatedAt))
				.max(Comparator.naturalOrder())
				.orElseThrow();
	}

	private static FinanciadorCatalogoView vista(
			FinanciadorCatalogo financiador,
			List<PlanCatalogo> planes,
			Long financiadorAdoptadoId,
			boolean actualizacionDisponible) {

		return vista(financiador, planes, actualizadoEn(financiador, planes), financiadorAdoptadoId,
				actualizacionDisponible);
	}

	private static FinanciadorCatalogoView vista(
			FinanciadorCatalogo financiador,
			List<PlanCatalogo> planes,
			Instant actualizadoEn,
			Long financiadorAdoptadoId,
			boolean actualizacionDisponible) {

		return FinanciadorCatalogoView.de(
				financiador,
				actualizadoEn,
				planes.stream().map(PlanCatalogoView::de).toList(),
				financiadorAdoptadoId,
				actualizacionDisponible);
	}

	private void auditar(
			String eventType,
			String entityType,
			long entityId,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				// Sin tenant: el catalogo es de la plataforma (audit_event admite NULL solo para
				// eventos de plataforma, ADR-0023).
				null,
				null,
				actor.accountId(),
				eventType,
				entityType,
				entityId,
				previousState,
				newState,
				detalles,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
