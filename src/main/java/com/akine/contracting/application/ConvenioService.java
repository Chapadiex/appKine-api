package com.akine.contracting.application;

import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.Vigencia;
import com.akine.contracting.domain.exception.ConvenioCodigoTakenException;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioSolapadoException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
import com.akine.contracting.domain.exception.SedeNoAccesibleException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenios de una sede con un plan de un financiador (M16, AKINE-03.05).
 *
 * <h2>LA REGLA QUE DEFINE ESTA ETAPA, Y COMO SE HACE CUMPLIR</h2>
 *
 * <p>Dos convenios de la misma {@code (consultorio, financiador, plan)} no se pueden solapar en el
 * tiempo (RN-M16-002). <b>Ningun indice de MySQL expresa eso</b>: 01/01–30/06 y 01/03–31/12 se
 * pisan y no comparten ningun valor de columna, y MySQL 8.4 no tiene exclusion constraints. La
 * regla la hace cumplir esta clase, y para que resista dos escrituras concurrentes hacen falta
 * <b>tres</b> cosas, no una:
 *
 * <ol>
 *   <li><b>{@code READ_COMMITTED}</b>, y no el {@code REPEATABLE READ} por defecto de InnoDB. Con
 *       REPEATABLE READ la foto de la transaccion se fija en la primera lectura consistente, que
 *       ocurre ANTES del lock: el lock se toma correctamente y despues se lee un mundo viejo en el
 *       que el convenio ajeno todavia no existe. Es la leccion que 05.02 pago con los turnos.</li>
 *   <li><b>La fila-lock creada en una transaccion aparte</b>, con
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE}. Ver {@link ConvenioLockIniciador}: crearla
 *       perezosamente aca produce deadlock entre las primeras N escrituras de una sede, y el
 *       {@code try/catch} no salva.</li>
 *   <li><b>El lock tomado ANTES de leer nada</b> del conjunto que se valida. Leer primero y
 *       bloquear despues es una escalada S a X entre dos transacciones simetricas, o sea un
 *       deadlock.</li>
 * </ol>
 *
 * <p>El orden de las tres operaciones —asegurar, bloquear, leer— es la parte que no se puede
 * cambiar sin romper la garantia, y por eso las mutaciones lo repiten identico.
 *
 * <h2>Lo que si sostiene un unique, y lo que no</h2>
 *
 * <pre>
 *   IGUALDAD       codigo unico entre los convenios vigentes de la sede  -&gt; uk_convenio_codigo_vigente
 *   SOLAPAMIENTO   dos periodos que se cruzan                            -&gt; lock + esta clase
 * </pre>
 *
 * <h2>Vigencia y ciclo de vida no son lo mismo, y M16 pide los dos</h2>
 *
 * <ul>
 *   <li><b>Cerrar la vigencia</b> (RF-M16-003) es {@link #editar} con {@code vigenciaHasta}. El
 *       convenio queda ACTIVO y consultable; lo unico que cambia es que deja de aplicarse despues
 *       de esa fecha. Es una correccion de calendario.</li>
 *   <li><b>Dar de baja</b> es {@link #darDeBaja}, exige motivo y saca el convenio del ciclo de
 *       vida.</li>
 * </ul>
 *
 * <p>Misma decision que 03.03 tomo para los planes, y por el mismo motivo: el caso borde "convenio
 * vencido" es exactamente un convenio ACTIVO con la vigencia cerrada.
 */
@Service
public class ConvenioService {

	private static final Logger log = LoggerFactory.getLogger(ConvenioService.class);

	private final ConvenioRepositoryPort convenios;
	private final ConvenioArancelRepositoryPort aranceles;
	private final ConvenioLockRepositoryPort locks;
	private final ConvenioLockIniciador lockIniciador;
	private final FinanciadorRepositoryPort financiadores;
	private final PlanCoberturaRepositoryPort planes;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	@SuppressWarnings("java:S107")
	public ConvenioService(
			ConvenioRepositoryPort convenios,
			ConvenioArancelRepositoryPort aranceles,
			ConvenioLockRepositoryPort locks,
			ConvenioLockIniciador lockIniciador,
			FinanciadorRepositoryPort financiadores,
			PlanCoberturaRepositoryPort planes,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.convenios = convenios;
		this.aranceles = aranceles;
		this.locks = locks;
		this.lockIniciador = lockIniciador;
		this.financiadores = financiadores;
		this.planes = planes;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Los convenios de una sede, activos e historicos, ordenados por nombre.
	 *
	 * <p>Devuelve tambien los dados de baja y los vencidos: la grilla de vigencias necesita
	 * mostrarlos para que se entienda por que una prestacion vieja se cobro a otro precio. Cada
	 * fila viaja con su {@code vigente} calculado contra {@code fecha}, asi que quien mira puede
	 * distinguir "dado de baja" de "vencido" sin adivinar.
	 */
	@Transactional(readOnly = true)
	public List<ConvenioView> listar(
			OperatingActor actor, long consultorioId, EstadoFiltro estado, LocalDate fecha) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Listar convenios");
		exigirSedeDelTenant(organizationId, consultorioId);

		EstadoFiltro filtro = estado == null ? EstadoFiltro.ACTIVO : estado;
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		return convenios.findAllByScopeOrderByNombreAsc(organizationId, consultorioId).stream()
				.filter(convenio -> switch (filtro) {
					case ACTIVO -> convenio.isActive();
					case INACTIVO -> !convenio.isActive();
					case TODOS -> true;
				})
				.map(convenio -> ConvenioView.de(convenio, contra))
				.toList();
	}

	/** Un convenio de esa sede. Un convenio INACTIVO se lee con 200, no con 404 (§38). */
	@Transactional(readOnly = true)
	public ConvenioView ver(
			OperatingActor actor, long consultorioId, long convenioId, LocalDate fecha) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Ver un convenio");
		exigirSedeDelTenant(organizationId, consultorioId);

		return ConvenioView.de(
				cargar(organizationId, consultorioId, convenioId),
				fecha == null ? LocalDate.now() : fecha);
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de un convenio (RF-M16-001).
	 *
	 * <p>Corre en {@code READ_COMMITTED} y toma el lock de la sede antes de leer el conjunto de
	 * convenios que compiten por el periodo. Ver la cabecera de la clase: las tres condiciones son
	 * necesarias y ninguna alcanza sola.
	 *
	 * <p>Valida, en este orden: sede del tenant (404), permiso sobre esa sede (403), financiador y
	 * plan del tenant (404), financiador y plan operables (409), y recien entonces el solapamiento
	 * (409). El orden importa: comprobar el permiso antes que la pertenencia de la sede convertiria
	 * un 404 en un 403 y eso confirmaria que la sede existe en otro tenant.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ConvenioView crear(
			OperatingActor actor, long consultorioId, ConvenioAltaCommand command) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Crear un convenio");
		exigirSedeDelTenant(organizationId, consultorioId);
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, "Crear un convenio");

		exigirFinanciadorOperable(organizationId, command.financiadorId());
		PlanCobertura plan = exigirPlanOperable(organizationId, command.planId());
		exigirPlanDelFinanciador(plan, command.financiadorId());

		String codigo = normalizar(command.codigo(), "El codigo del convenio es obligatorio");
		String nombre = normalizar(command.nombre(), "El nombre del convenio es obligatorio");

		Convenio convenio = new Convenio(
				organizationId,
				consultorioId,
				command.financiadorId(),
				command.planId(),
				codigo,
				nombre,
				command.modalidad(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.moneda(),
				Boolean.TRUE.equals(command.requiereOrden()),
				Boolean.TRUE.equals(command.requiereAutorizacion()),
				// Ausente se toma como TRUE, al reves que los otros dos: pedir la credencial es lo
				// normal en una prestacion financiada, y el default menos invasivo aca es el que
				// hace que se pida el dato en vez de omitirlo en silencio. Mismo criterio que
				// PlanCobertura en 03.03.
				command.requiereCredencial() == null || command.requiereCredencial(),
				command.limiteSesionesMensual(),
				command.documentacionRequerida(),
				command.observaciones());

		// EL ORDEN DE ESTAS TRES LINEAS ES LA GARANTIA. Ver la cabecera.
		lockIniciador.asegurar(organizationId, consultorioId);
		BloqueoDeConvenios.tomar(locks, organizationId, consultorioId);
		exigirSinSolapamiento(
				organizationId, consultorioId, command.financiadorId(), command.planId(),
				convenio.vigencia(), null);

		Convenio creado = persistir(convenio, codigo);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("codigo", codigo);
		detalles.put("financiadorId", String.valueOf(command.financiadorId()));
		detalles.put("planId", String.valueOf(command.planId()));
		detalles.put("vigencia", creado.vigencia().toString());
		detalles.put("moneda", creado.getMoneda());
		auditar(AuditEvents.CONVENIO_CREATED, creado, actor, null, "ACTIVO", null, detalles);

		log.info("Convenio creado: convenioId={} consultorioId={}", creado.getId(), consultorioId);
		return ConvenioView.de(creado, LocalDate.now());
	}

	/**
	 * Edicion parcial, incluido el cierre de vigencia (RF-M16-002 y RF-M16-003).
	 *
	 * <p><b>Tambien toma el lock</b>, y no solo el alta: mover {@code vigenciaDesde} hacia atras o
	 * {@code vigenciaHasta} hacia adelante puede crear exactamente el solapamiento que el alta
	 * impide. Una etapa que valide el solapamiento solo al crear deja abierta la puerta mas ancha.
	 *
	 * <p>Un convenio INACTIVO no se edita: 409. Reabrir la ficha de algo dado de baja reescribiria
	 * el historico que RN-M16-003 protege.
	 *
	 * <p><b>Un convenio de un financiador o un plan dado de baja SI se puede editar</b>, y eso no
	 * es una inconsistencia con el alta: dejar de trabajar con una obra social no puede tener como
	 * efecto que su convenio quede congelado con un error de tipeo. Lo que la baja impide es firmar
	 * convenios nuevos, no corregir los que hay.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ConvenioView editar(
			OperatingActor actor,
			long consultorioId,
			long convenioId,
			ConvenioEdicionCommand command) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Editar un convenio");
		exigirSedeDelTenant(organizationId, consultorioId);
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, "Editar un convenio");

		lockIniciador.asegurar(organizationId, consultorioId);
		BloqueoDeConvenios.tomar(locks, organizationId, consultorioId);

		Convenio convenio = cargar(organizationId, consultorioId, convenioId);
		exigirOperable(convenio, "editar");
		exigirVersion(convenio, command.expectedVersion());

		String nombre = command.nombre() == null
				? null
				: normalizar(command.nombre(), "El nombre del convenio es obligatorio");
		Map<String, String> detalles = cambios(convenio, nombre, command);

		convenio.updateDatos(
				nombre,
				command.modalidad(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.requiereOrden(),
				command.requiereAutorizacion(),
				command.requiereCredencial(),
				command.limiteSesionesMensual(),
				command.documentacionRequerida(),
				command.observaciones());

		exigirSinSolapamiento(
				organizationId, consultorioId, convenio.getFinanciadorId(), convenio.getPlanId(),
				convenio.vigencia(), convenioId);

		Convenio guardado;
		try {
			guardado = convenios.saveAndFlush(convenio);
		} catch (DataIntegrityViolationException choque) {
			// El codigo es updatable = false, asi que en una edicion no puede chocar ningun unique
			// de esta tabla. Lo que llegue aca no se reconoce y se deja propagar: un 500 honesto
			// antes que un 409 inventado. Mismo criterio que FinanciadorService.editar.
			log.warn("Edicion de convenio rechazada por la base: convenioId={}", convenioId);
			throw choque;
		}

		auditar(AuditEvents.CONVENIO_UPDATED, guardado, actor, null, null, null, detalles);
		return ConvenioView.de(guardado, LocalDate.now());
	}

	/**
	 * Baja logica con motivo obligatorio.
	 *
	 * <p>NO borra nada (§38): el convenio queda INACTIVO, sigue siendo legible y conserva su codigo
	 * y su nombre. Libera ese codigo para un convenio nuevo de la misma sede —el unique lleva
	 * {@code deleted_key}— y libera tambien el PERIODO, porque el no-solapamiento solo mira los
	 * activos: se puede volver a firmar con el mismo plan para las mismas fechas.
	 *
	 * <p><b>No cascadea a los aranceles y no se bloquea por tenerlos.</b> Dejan de resolver porque
	 * su convenio dejo de resolver, que es suficiente; obligar a darlos de baja uno por uno seria
	 * burocracia sin garantia a cambio. El numero queda en la auditoria, mismo criterio que la baja
	 * de un financiador en 03.03.
	 *
	 * <p><b>Lo ya liquidado bajo este convenio no se toca.</b> Sigue explicandose con la copia
	 * congelada que guardo al devengar; este metodo no tiene forma de alcanzarlo, porque esta clase
	 * no conoce a {@code billing}.
	 *
	 * <p>No toma el lock: quitar un convenio del conjunto activo nunca puede crear un solapamiento.
	 */
	@Transactional
	public ConvenioView darDeBaja(
			OperatingActor actor, long consultorioId, long convenioId, String motivo) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Dar de baja un convenio");
		exigirSedeDelTenant(organizationId, consultorioId);
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, "Dar de baja un convenio");

		Convenio convenio = cargar(organizationId, consultorioId, convenioId);
		exigirOperable(convenio, "dar de baja");

		long arancelesActivos = aranceles.countActivosDeConvenio(organizationId, convenioId);
		convenio.deactivate(Instant.now(), exigirMotivo(motivo));
		Convenio guardado = convenios.save(convenio);

		auditar(AuditEvents.CONVENIO_DEACTIVATED, guardado, actor, "ACTIVO", "INACTIVO", motivo,
				Map.of("arancelesActivos", String.valueOf(arancelesActivos)));

		log.info("Convenio dado de baja: convenioId={} arancelesActivos={}",
				convenioId, arancelesActivos);
		return ConvenioView.de(guardado, LocalDate.now());
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * RN-M16-002. <b>Se llama SIEMPRE con el lock ya tomado.</b>
	 *
	 * <p>Sin el lock esta comprobacion es decorativa: dos transacciones concurrentes leen cada una
	 * un conjunto en el que la otra fila todavia no esta, las dos concluyen "no se solapa" y las
	 * dos escriben. El lock es lo que hace que la segunda vea a la primera.
	 *
	 * @param excluirId el propio convenio cuando la operacion es una edicion. Sin esto, editar
	 *                  cualquier cosa de un convenio fallaria porque se solapa consigo mismo
	 */
	private void exigirSinSolapamiento(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			Vigencia vigencia,
			Long excluirId) {

		convenios.findActivosPorAlcance(organizationId, consultorioId, financiadorId, planId).stream()
				.filter(existente -> !existente.getId().equals(excluirId))
				.filter(existente -> existente.vigencia().seSolapaCon(vigencia))
				.findFirst()
				.ifPresent(choque -> {
					log.info("Convenio rechazado por solapamiento: choca con convenioId={}",
							choque.getId());
					throw new ConvenioSolapadoException(
							choque.getId(), choque.vigencia().toString());
				});
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new SedeNoAccesibleException(consultorioId));
	}

	private void exigirFinanciadorOperable(long organizationId, long financiadorId) {
		if (!financiadores.findByIdAndOrganizationId(financiadorId, organizationId)
				.orElseThrow(() -> new FinanciadorNotAccessibleException(financiadorId))
				.isOperable()) {

			log.info("Alta de convenio rechazada: financiador dado de baja. financiadorId={}",
					financiadorId);
			throw new FinanciadorInactivoException(financiadorId);
		}
	}

	private PlanCobertura exigirPlanOperable(long organizationId, long planId) {
		PlanCobertura plan = planes.findByIdAndOrganizationId(planId, organizationId)
				.orElseThrow(() -> new PlanNotAccessibleException(planId));

		if (!plan.isOperable()) {
			log.info("Alta de convenio rechazada: plan dado de baja. planId={}", planId);
			throw new PlanYaInactivoException(planId, "referenciar");
		}
		return plan;
	}

	/**
	 * El plan tiene que ser del financiador declarado (RN-M15-001).
	 *
	 * <p>Los dos ids llegan en el mismo cuerpo y nada obliga a que sean coherentes. Sin esta
	 * comprobacion se podria firmar un convenio "con OSDE, plan de Swiss Medical", que es un dato
	 * que despues nadie sabe interpretar. Responde 404 sobre el plan y no 400: bajo ese financiador,
	 * ese plan no existe.
	 */
	private static void exigirPlanDelFinanciador(PlanCobertura plan, long financiadorId) {
		if (!plan.getFinanciadorId().equals(financiadorId)) {
			throw new PlanNotAccessibleException(plan.getId());
		}
	}

	private Convenio cargar(long organizationId, long consultorioId, long convenioId) {
		return convenios.findByIdAndScope(convenioId, organizationId, consultorioId)
				.orElseThrow(() -> new ConvenioNotAccessibleException(convenioId));
	}

	private static void exigirOperable(Convenio convenio, String operacion) {
		if (!convenio.isOperable()) {
			log.info("Operacion sobre convenio inactivo rechazada: convenioId={} operacion={}",
					convenio.getId(), operacion);
			throw new ConvenioYaInactivoException(convenio.getId(), operacion);
		}
	}

	private static void exigirVersion(Convenio convenio, long esperada) {
		if (convenio.getVersion() != esperada) {
			throw new OptimisticLockingFailureException(
					"El convenio fue modificado por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private Convenio persistir(Convenio convenio, String codigo) {
		try {
			return convenios.saveAndFlush(convenio);
		} catch (DataIntegrityViolationException choque) {
			// Solo puede chocar uk_convenio_codigo_vigente: el resto de las invariantes se
			// comprobaron antes. Despues de un flush fallido NO se toca la sesion JPA.
			log.info("Alta de convenio rechazada por unique: codigo={}", codigo);
			throw new ConvenioCodigoTakenException(codigo);
		}
	}

	/**
	 * Los cambios efectivos, para el detalle de la auditoria.
	 *
	 * <p>La vigencia entra siempre que cambie, y esa es la linea que hace auditable RF-M16-003:
	 * sin ella nadie podria responder desde cuando un convenio dejo de aplicarse. §37 pide
	 * auditoria reforzada de importes y vigencias.
	 */
	private static Map<String, String> cambios(
			Convenio convenio, String nombre, ConvenioEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(convenio.getNombre())) {
			detalles.put("nombre", convenio.getNombre() + " -> " + nombre);
		}
		if (command.modalidad() != null && command.modalidad() != convenio.getModalidad()) {
			detalles.put("modalidad", convenio.getModalidad() + " -> " + command.modalidad());
		}
		LocalDate nuevoDesde =
				command.vigenciaDesde() == null ? convenio.getVigenciaDesde() : command.vigenciaDesde();
		LocalDate nuevoHasta =
				command.vigenciaHasta() == null ? convenio.getVigenciaHasta() : command.vigenciaHasta();
		if (!nuevoDesde.equals(convenio.getVigenciaDesde())
				|| !java.util.Objects.equals(nuevoHasta, convenio.getVigenciaHasta())) {
			detalles.put("vigencia",
					convenio.vigencia() + " -> " + new Vigencia(nuevoDesde, nuevoHasta));
		}
		return detalles;
	}

	@SuppressWarnings("java:S107")
	private void auditar(
			String eventType,
			Convenio convenio,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				convenio.getOrganizationId(),
				convenio.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_CONVENIO,
				convenio.getId(),
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
			throw new IllegalArgumentException("La baja de un convenio exige un motivo declarado");
		}
		return motivo.strip();
	}
}
