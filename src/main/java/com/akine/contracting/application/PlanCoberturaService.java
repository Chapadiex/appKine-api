package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.PlanCodigoTakenException;
import com.akine.contracting.domain.exception.PlanNombreTakenException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Los planes de cobertura de un financiador (M15).
 *
 * <h2>RN-M15-001 se cumple en tres lugares y hacen falta los tres</h2>
 *
 * <pre>
 *   BASE         financiador_id NOT NULL con FK RESTRICT (V41).
 *   ENTIDAD      la columna es updatable = false: un plan no se muda de financiador.
 *   APLICACION   el financiador se resuelve por la ruta, ACOTADO AL TENANT, antes de escribir.
 * </pre>
 *
 * <p>El tercero es el que la base no puede dar: la FK garantiza que el id exista, no que sea de
 * la organizacion del request. Sin la consulta previa, un {@code financiadorId} ajeno crearia un
 * plan con el {@code organization_id} del atacante y el {@code financiador_id} de la victima, y
 * la FK lo aceptaria sin objetar nada. Es la misma trampa que {@code OfertaService} documenta
 * para {@code consultorioId}.
 *
 * <h2>Vigencia y ciclo de vida no son lo mismo, y RF-M15-005 pide los dos</h2>
 *
 * <p>"Editar/finalizar plan" son dos operaciones distintas sobre la misma fila:
 *
 * <ul>
 *   <li><b>Cerrar la vigencia</b> es {@link #editar} con {@code vigenciaHasta}. El plan queda
 *       ACTIVO y consultable; lo unico que cambia es que deja de ofrecerse para selecciones
 *       posteriores a esa fecha. Es una correccion de calendario.</li>
 *   <li><b>Dar de baja</b> es {@link #darDeBaja}, exige motivo, es irreversible y saca el plan del
 *       ciclo de vida.</li>
 * </ul>
 *
 * <p>Colapsarlas seria perder el caso borde que la etapa nombra: "plan sin nuevas altas pero con
 * pacientes vigentes" es exactamente un plan ACTIVO con la vigencia cerrada.
 */
@Service
public class PlanCoberturaService {

	private static final Logger log = LoggerFactory.getLogger(PlanCoberturaService.class);

	private final PlanCoberturaRepositoryPort planes;
	private final FinanciadorRepositoryPort financiadores;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public PlanCoberturaService(
			PlanCoberturaRepositoryPort planes,
			FinanciadorRepositoryPort financiadores,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.planes = planes;
		this.financiadores = financiadores;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Los planes de un financiador, activos e historicos, ordenados por nombre.
	 *
	 * <p>Devuelve tambien los dados de baja y los de vigencia vencida (RN-M15-003): la pantalla
	 * necesita mostrarlos para que se entienda por que una cobertura vieja apunta a algo que ya no
	 * se ofrece. Cada fila viaja con su {@code vigente} calculado contra {@code fecha}, asi que
	 * quien mira puede distinguir "dado de baja" de "vencido" sin adivinar.
	 *
	 * <p>{@code estado} filtra el ciclo de vida y por defecto trae solo los ACTIVOS, que es lo que
	 * cumple RN-M15-002 en un selector.
	 */
	@Transactional(readOnly = true)
	public List<PlanCoberturaView> listar(
			OperatingActor actor, long financiadorId, EstadoFiltro estado, LocalDate fecha) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Listar planes");
		exigirFinanciadorDelTenant(organizationId, financiadorId);

		EstadoFiltro filtro = estado == null ? EstadoFiltro.ACTIVO : estado;
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		return planes
				.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(organizationId, financiadorId)
				.stream()
				.filter(plan -> switch (filtro) {
					case ACTIVO -> plan.isActive();
					case INACTIVO -> !plan.isActive();
					case TODOS -> true;
				})
				.map(plan -> PlanCoberturaView.de(plan, contra))
				.toList();
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de un plan bajo un financiador (RF-M15-004).
	 *
	 * <p><b>Un financiador dado de baja no admite planes nuevos: 409 {@code financiador-inactivo}.</b>
	 * Es la contracara de que la baja no cascadee — lo que se impide es lo nuevo, no lo que ya
	 * existe. Mismo tratamiento, y mismo {@code type}, que {@code servicio-inactivo} en M27.
	 *
	 * <p>No consulta si el codigo existe antes de insertar, por el mismo motivo que el alta de
	 * financiador: entre el SELECT y el INSERT hay una ventana, el unique no la tiene, y el
	 * pre-chequeo ademas romperia el reuso de un codigo liberado por una baja.
	 */
	@Transactional
	public PlanCoberturaView crear(
			OperatingActor actor, long financiadorId, PlanAltaCommand command) {

		AutorizacionDeCatalogo.exigirGestionDelCatalogo(permissionGuard, actor, "Crear un plan");
		long organizationId = actor.contextOrganizationId();

		Financiador financiador = exigirFinanciadorDelTenant(organizationId, financiadorId);
		if (!financiador.isOperable()) {
			log.info("Alta de plan rechazada: financiador dado de baja. financiadorId={}",
					financiadorId);
			throw new FinanciadorInactivoException(financiadorId);
		}

		String codigo = normalizar(command.codigo(), "El codigo del plan es obligatorio");
		String nombre = normalizar(command.nombre(), "El nombre del plan es obligatorio");

		PlanCobertura plan = new PlanCobertura(
				organizationId,
				financiadorId,
				codigo,
				nombre,
				command.descripcion(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				// Ausente se toma como false: es el valor menos invasivo y el que no le impone a
				// una cobertura futura un tramite que nadie declaro.
				Boolean.TRUE.equals(command.requiereAutorizacion()),
				// Ausente se toma como TRUE, al reves que el anterior. Pedir la credencial es lo
				// normal en una cobertura financiada, y el default menos invasivo aca es el que
				// hace que M08 pida el dato en vez de omitirlo en silencio.
				command.requiereCredencial() == null || command.requiereCredencial(),
				command.copago(),
				command.moneda());

		PlanCobertura creado = persistir(plan, codigo);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("codigo", codigo);
		detalles.put("financiadorId", String.valueOf(financiadorId));
		detalles.put("vigenciaDesde", String.valueOf(creado.getVigenciaDesde()));
		auditar(AuditEvents.PLAN_COBERTURA_CREATED, creado, actor, null, "ACTIVO", null, detalles);

		log.info("Plan de cobertura creado: planId={} financiadorId={}", creado.getId(), financiadorId);
		return PlanCoberturaView.de(creado, LocalDate.now());
	}

	/**
	 * Edicion parcial, incluido el cierre de vigencia (RF-M15-005).
	 *
	 * <p>Un plan INACTIVO no se edita: 409. Reabrir la ficha de algo dado de baja reescribiria el
	 * historico que RN-M15-003 protege.
	 *
	 * <p><b>Un plan de un financiador dado de baja SI se puede editar</b>, y eso no es una
	 * inconsistencia con el alta: dejar de trabajar con una obra social no puede tener como efecto
	 * que sus planes queden congelados con un error de tipeo. Lo que la baja del financiador
	 * impide es crear planes nuevos y que los existentes se ofrezcan, no corregirlos.
	 */
	@Transactional
	public PlanCoberturaView editar(
			OperatingActor actor, long financiadorId, long planId, PlanEdicionCommand command) {

		AutorizacionDeCatalogo.exigirGestionDelCatalogo(permissionGuard, actor, "Editar un plan");
		long organizationId = actor.contextOrganizationId();

		PlanCobertura plan = cargar(organizationId, financiadorId, planId);
		exigirOperable(plan, "editar");
		exigirVersion(plan, command.expectedVersion());

		String nombre = command.nombre() == null
				? null
				: normalizar(command.nombre(), "El nombre del plan es obligatorio");
		Map<String, String> detalles = cambios(plan, nombre, command);

		plan.updateDatos(
				nombre,
				command.descripcion(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.requiereAutorizacion(),
				command.requiereCredencial(),
				command.copago(),
				command.moneda());

		PlanCobertura guardado;
		try {
			guardado = planes.saveAndFlush(plan);
		} catch (DataIntegrityViolationException choque) {
			// Solo el nombre puede chocar en una edicion: el codigo es updatable = false. Lo que no
			// se reconoce se deja propagar — un 500 honesto antes que un 409 inventado. Ver el
			// mismo comentario, mas largo, en FinanciadorService.editar.
			if (indiceContiene(choque, "_nombre_")) {
				log.info("Edicion de plan rechazada por nombre repetido: planId={}", planId);
				throw new PlanNombreTakenException();
			}
			throw choque;
		}

		auditar(AuditEvents.PLAN_COBERTURA_UPDATED, guardado, actor, null, null, null, detalles);
		return PlanCoberturaView.de(guardado, LocalDate.now());
	}

	/**
	 * Baja logica con motivo obligatorio (RF-M15-005).
	 *
	 * <p>NO borra nada: el plan queda INACTIVO, sigue siendo legible y conserva su nombre y su
	 * codigo. Libera ese codigo y ese nombre para un plan nuevo del mismo financiador —el unique
	 * lleva {@code deleted_key}— y deja de ofrecerse en los selectores (RN-M15-002).
	 *
	 * <p><b>Las coberturas ya firmadas bajo este plan no se tocan.</b> Siguen resolviendo con la
	 * copia congelada que guardaron al firmarse; este metodo no tiene forma de alcanzarlas, porque
	 * esta clase no conoce a M08.
	 */
	@Transactional
	public PlanCoberturaView darDeBaja(
			OperatingActor actor, long financiadorId, long planId, String motivo) {

		AutorizacionDeCatalogo.exigirGestionDelCatalogo(
				permissionGuard, actor, "Dar de baja un plan");
		long organizationId = actor.contextOrganizationId();

		PlanCobertura plan = cargar(organizationId, financiadorId, planId);
		exigirOperable(plan, "dar de baja");

		Instant ahora = Instant.now();
		plan.deactivate(ahora, exigirMotivo(motivo));
		PlanCobertura guardado = planes.save(plan);

		auditar(AuditEvents.PLAN_COBERTURA_DEACTIVATED, guardado, actor,
				"ACTIVO", "INACTIVO", motivo, Map.of());

		log.info("Plan de cobertura dado de baja: planId={}", planId);
		return PlanCoberturaView.de(guardado, LocalDate.now());
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * El financiador de la ruta, acotado al tenant. 404 si no existe o es de otra organizacion.
	 *
	 * <p>Se resuelve contra la BASE y siempre ANTES de mutar nada: ver la cabecera de la clase.
	 */
	private Financiador exigirFinanciadorDelTenant(long organizationId, long financiadorId) {
		return financiadores.findByIdAndOrganizationId(financiadorId, organizationId)
				.orElseThrow(() -> new FinanciadorNotAccessibleException(financiadorId));
	}

	/**
	 * El plan de ESE financiador y ESE tenant.
	 *
	 * <p>Las tres columnas del filtro son necesarias y ninguna es redundante: sin
	 * {@code organizationId} un id ajeno resuelve, y sin {@code financiadorId} un plan de otro
	 * financiador respondería a una ruta que no le corresponde — una respuesta que miente sobre a
	 * quien pertenece el plan.
	 */
	private PlanCobertura cargar(long organizationId, long financiadorId, long planId) {
		return planes
				.findByIdAndOrganizationIdAndFinanciadorId(planId, organizationId, financiadorId)
				.orElseThrow(() -> new PlanNotAccessibleException(planId));
	}

	private static void exigirOperable(PlanCobertura plan, String operacion) {
		if (!plan.isOperable()) {
			log.info("Operacion sobre plan inactivo rechazada: planId={} operacion={}",
					plan.getId(), operacion);
			throw new PlanYaInactivoException(plan.getId(), operacion);
		}
	}

	private static void exigirVersion(PlanCobertura plan, long esperada) {
		if (plan.getVersion() != esperada) {
			throw new OptimisticLockingFailureException("El plan fue modificado por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private PlanCobertura persistir(PlanCobertura plan, String codigo) {
		try {
			return planes.saveAndFlush(plan);
		} catch (DataIntegrityViolationException choque) {
			log.info("Alta de plan rechazada por unique: codigo={}", codigo);
			if (indiceContiene(choque, "_nombre_")) {
				throw new PlanNombreTakenException();
			}
			throw new PlanCodigoTakenException(codigo);
		}
	}

	private static boolean indiceContiene(DataIntegrityViolationException choque, String fragmento) {
		Throwable causa = choque.getMostSpecificCause();
		String mensaje = causa.getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains(fragmento);
	}

	/**
	 * Los cambios efectivos, para el detalle de la auditoria. Solo lo que realmente cambia.
	 *
	 * <p>El cierre de vigencia entra como {@code vigenciaHasta}, que es la linea que hace
	 * auditable RF-M15-005: sin ella nadie podria responder desde cuando un plan dejo de
	 * ofrecerse.
	 */
	private static Map<String, String> cambios(
			PlanCobertura plan, String nombre, PlanEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(plan.getNombre())) {
			detalles.put("nombre", plan.getNombre() + " -> " + nombre);
		}
		if (command.vigenciaDesde() != null
				&& !command.vigenciaDesde().equals(plan.getVigenciaDesde())) {
			detalles.put("vigenciaDesde", plan.getVigenciaDesde() + " -> " + command.vigenciaDesde());
		}
		if (command.vigenciaHasta() != null
				&& !command.vigenciaHasta().equals(plan.getVigenciaHasta())) {
			detalles.put("vigenciaHasta", plan.getVigenciaHasta() + " -> " + command.vigenciaHasta());
		}
		if (command.copago() != null
				&& (plan.getCopago() == null || plan.getCopago().compareTo(command.copago()) != 0)) {
			detalles.put("copago", plan.getCopago() + " -> " + command.copago());
		}
		return detalles;
	}

	private void auditar(
			String eventType,
			PlanCobertura plan,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				plan.getOrganizationId(),
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_PLAN,
				plan.getId(),
				previousState,
				newState,
				detalles,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}

	private static String normalizar(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("La baja de un plan exige un motivo declarado");
		}
		return motivo.strip();
	}
}
