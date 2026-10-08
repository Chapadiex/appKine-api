package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.exception.FinanciadorCodigoTakenException;
import com.akine.contracting.domain.exception.FinanciadorCuitTakenException;
import com.akine.contracting.domain.exception.FinanciadorNombreTakenException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.FinanciadorYaInactivoException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El catalogo de financiadores de una organizacion (M15): CON QUIEN trabaja un centro.
 *
 * <h2>Que garantiza esta clase, y que deliberadamente no</h2>
 *
 * <p>Garantiza que dos financiadores vigentes de la misma organizacion no compartan codigo,
 * nombre ni CUIT; que el codigo sea inmutable; que la baja sea logica y con motivo; y que nada de
 * esto cruce el borde del tenant.
 *
 * <p><b>NO garantiza que una cobertura firmada ayer no cambie si hoy alguien renombra el
 * financiador</b>, porque eso no se puede garantizar desde aca: se garantiza porque el consumidor
 * copia {@code ReferenciaDeCobertura} a sus propias columnas. Lo que esta clase aporta a esa
 * garantia es la mitad estructural —codigo inmutable y sin borrado fisico—, y esta desarrollado
 * en el javadoc de {@link Financiador}.
 *
 * <h2>La baja NO cascadea, y ese es el caso que rompe el diseno</h2>
 *
 * <p>Dar de baja un financiador con planes activos y coberturas firmadas <b>no toca ninguno de
 * ellos</b>. RN-M15-003 prohibe eliminar historicos: los planes conservan sus filas, las
 * coberturas siguen resolviendo, y lo unico que se impide es crear planes NUEVOS bajo el (409
 * {@code financiador-inactivo}, desde {@code PlanCoberturaService}) y que sus planes se ofrezcan
 * para selecciones nuevas (RN-M15-002, resuelto al leer por el {@code spi}).
 *
 * <p>La garantia es <b>estructural</b> y no una promesa de este javadoc: {@link Financiador} no
 * tiene ninguna relacion JPA hacia sus planes, y esta clase recibe el puerto de planes
 * <b>solo para contarlos</b> —{@code countBy...}, la unica firma de lectura que usa de el—. No
 * hay por donde cascadear aunque alguien quisiera; para introducir una cascada habria primero que
 * agregar un metodo de escritura a ese puerto.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Leer: pertenencia al tenant. Mutar: {@code convenio:manage} evaluado con la sede del
 * contexto. Las dos formas, y los dos huecos que dejan, estan en {@link AutorizacionDeCatalogo} y
 * en {@code contracting.domain.PermissionCodes}. <b>Esta etapa no crea ningun codigo de
 * permiso</b>: {@code convenio:manage} ya estaba en el catalogo de la matriz §5. Lo que si hace
 * es darle asignacion base, que es una enmienda declarada de la matriz (§13).
 */
@Service
public class FinanciadorService {

	private static final Logger log = LoggerFactory.getLogger(FinanciadorService.class);

	private final FinanciadorRepositoryPort financiadores;
	private final PlanCoberturaRepositoryPort planes;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public FinanciadorService(
			FinanciadorRepositoryPort financiadores,
			PlanCoberturaRepositoryPort planes,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.financiadores = financiadores;
		this.planes = planes;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Buscador de financiadores de la organizacion, ordenado por nombre (RF-M15-006).
	 *
	 * <p>Devuelve los INACTIVOS cuando se piden explicitamente, y con 200: RN-M15-003 exige que
	 * los historicos sigan resolviendo, y responder "no existe" seria borrar historia por la
	 * puerta de atras. Con el filtro por defecto ({@code ACTIVO}) un selector nunca ofrece un
	 * financiador dado de baja.
	 */
	@Transactional(readOnly = true)
	public List<FinanciadorView> buscar(OperatingActor actor, FinanciadorBusqueda filtros) {
		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Buscar financiadores");
		return financiadores
				.buscar(organizationId, filtros.patron(), filtros.activoFiltro(), filtros.tipoFiltro())
				.stream()
				.map(FinanciadorView::de)
				.toList();
	}

	/** Un financiador de la organizacion, activo o no. 404 si no existe o es de otro tenant. */
	@Transactional(readOnly = true)
	public FinanciadorView ver(OperatingActor actor, long financiadorId) {
		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Ver un financiador");
		return FinanciadorView.de(cargar(organizationId, financiadorId));
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de un financiador (RF-M15-001).
	 *
	 * <p><b>No consulta si el codigo existe antes de insertar</b>, y es deliberado: entre un
	 * SELECT de comprobacion y el INSERT hay una ventana en la que otro request entra. La unica
	 * comprobacion sin ventana es el unique, asi que el 409 se produce traduciendo su violacion.
	 * El efecto de lado que importa, y que un pre-chequeo romperia: un codigo, un nombre o un CUIT
	 * liberado por una baja logica <b>se puede reusar</b>, porque el unique lleva
	 * {@code deleted_key} como discriminador y nada en este metodo lo impide.
	 */
	@Transactional
	public FinanciadorView crear(OperatingActor actor, FinanciadorAltaCommand command) {
		AutorizacionDeCatalogo.exigirGestionDelCatalogo(
				permissionGuard, actor, "Crear un financiador");
		long organizationId = actor.contextOrganizationId();

		String codigo = normalizar(command.codigo(), "El codigo del financiador es obligatorio");
		String nombre = normalizar(command.nombre(), "El nombre del financiador es obligatorio");

		Financiador financiador = new Financiador(
				organizationId,
				codigo,
				nombre,
				command.tipo(),
				command.cuit(),
				command.emailContacto(),
				command.telefonoContacto(),
				command.observaciones());

		Financiador creado = persistir(financiador, codigo);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("codigo", codigo);
		detalles.put("tipo", creado.getTipo().name());
		auditar(AuditEvents.FINANCIADOR_CREATED, creado, actor, null, "ACTIVO", null, detalles);

		log.info("Financiador creado: financiadorId={} codigo={}", creado.getId(), codigo);
		return FinanciadorView.de(creado);
	}

	/**
	 * Edicion parcial (RF-M15-002).
	 *
	 * <p>Un financiador INACTIVO no se edita: 409. Reabrir la ficha de algo dado de baja para
	 * cambiarle el nombre reescribiria el historico que RN-M15-003 protege — y no existe la
	 * reactivacion: un financiador que vuelve es un alta nueva, no una baja deshecha.
	 */
	@Transactional
	public FinanciadorView editar(
			OperatingActor actor, long financiadorId, FinanciadorEdicionCommand command) {

		AutorizacionDeCatalogo.exigirGestionDelCatalogo(
				permissionGuard, actor, "Editar un financiador");
		long organizationId = actor.contextOrganizationId();

		Financiador financiador = cargar(organizationId, financiadorId);
		exigirOperable(financiador, "editar");
		exigirVersion(financiador, command.expectedVersion());

		String nombre = command.nombre() == null
				? null
				: normalizar(command.nombre(), "El nombre del financiador es obligatorio");
		Map<String, String> detalles = cambios(financiador, nombre, command);

		financiador.updateDatos(
				nombre,
				command.tipo(),
				command.cuit(),
				command.emailContacto(),
				command.telefonoContacto(),
				command.observaciones());

		Financiador guardado;
		try {
			guardado = financiadores.saveAndFlush(financiador);
		} catch (DataIntegrityViolationException choque) {
			// Una edicion puede violar el unique de nombre o el de CUIT —el codigo es
			// updatable = false y ni siquiera viaja en el comando—, pero
			// DataIntegrityViolationException NO la levanta solo un unique: un valor demasiado
			// largo para su columna, bajo el modo estricto de MySQL, llega por la misma puerta.
			// Traducir el bloque entero a "nombre repetido" produciria un 409 que MIENTE. Por eso
			// se discrimina por el nombre del indice y lo que no se reconoce se deja propagar: un
			// 500 honesto es mejor que un 409 inventado. Mismo criterio que ServicioService.
			throw traducirEdicion(choque, nombre);
		}

		auditar(AuditEvents.FINANCIADOR_UPDATED, guardado, actor, null, null, null, detalles);
		return FinanciadorView.de(guardado);
	}

	/**
	 * Baja logica con motivo obligatorio (RF-M15-003).
	 *
	 * <p>NO borra nada y NO cascadea: es el caso que rompe el diseno y esta desarrollado en la
	 * cabecera de la clase. Los planes del financiador conservan sus filas y las coberturas ya
	 * firmadas siguen resolviendo.
	 *
	 * <p><b>Tener planes activos no bloquea la baja</b>, y esa es una decision, no un descuido:
	 * bloquearla obligaria a dar de baja los planes uno por uno para poder dejar de trabajar con
	 * una obra social, que es trabajo burocratico sin ninguna garantia a cambio. Lo que si se hace
	 * es <b>contarlos y dejar el numero en la auditoria</b>, para que seis meses despues se sepa
	 * cuanto arrastraba esa baja.
	 */
	@Transactional
	public FinanciadorView darDeBaja(OperatingActor actor, long financiadorId, String motivo) {
		AutorizacionDeCatalogo.exigirGestionDelCatalogo(
				permissionGuard, actor, "Dar de baja un financiador");
		long organizationId = actor.contextOrganizationId();

		Financiador financiador = cargar(organizationId, financiadorId);
		exigirOperable(financiador, "dar de baja");

		long planesActivos =
				planes.countByOrganizationIdAndFinanciadorIdAndActive(organizationId, financiadorId, true);

		Instant ahora = Instant.now();
		financiador.deactivate(ahora, exigirMotivo(motivo));
		Financiador guardado = financiadores.save(financiador);

		auditar(AuditEvents.FINANCIADOR_DEACTIVATED, guardado, actor, "ACTIVO", "INACTIVO", motivo,
				Map.of("planesActivos", String.valueOf(planesActivos)));

		log.info("Financiador dado de baja: financiadorId={} planesActivos={}",
				financiadorId, planesActivos);
		return FinanciadorView.de(guardado);
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	private Financiador cargar(long organizationId, long financiadorId) {
		return financiadores.findByIdAndOrganizationId(financiadorId, organizationId)
				.orElseThrow(() -> new FinanciadorNotAccessibleException(financiadorId));
	}

	private static void exigirOperable(Financiador financiador, String operacion) {
		if (!financiador.isOperable()) {
			log.info("Operacion sobre financiador inactivo rechazada: financiadorId={} operacion={}",
					financiador.getId(), operacion);
			throw new FinanciadorYaInactivoException(financiador.getId(), operacion);
		}
	}

	private static void exigirVersion(Financiador financiador, long esperada) {
		if (financiador.getVersion() != esperada) {
			// GlobalExceptionHandler lo mapea a 409 concurrent-modification (DP-21), igual que la
			// subclase de JPA.
			throw new OptimisticLockingFailureException(
					"El financiador fue modificado por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/**
	 * Guarda forzando el flush y traduce el choque de unique.
	 *
	 * <p><b>Como se distingue cual de los tres uniques choco.</b> MySQL nombra el indice violado
	 * en el mensaje del error, y ese nombre es la unica senal disponible: la excepcion de Spring
	 * no lleva ningun campo estructurado con la constraint. La degradacion es benigna: los tres
	 * son 409 con {@code type} distinto, y el peor caso es un mensaje que nombra el campo
	 * equivocado, nunca un dato mal guardado.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA</b> —ni una lectura
	 * para averiguar cual choco—. La especificacion lo prohibe y lo que sale de ahi es un 500 en
	 * vez de un 409 legitimo.
	 */
	private Financiador persistir(Financiador financiador, String codigo) {
		try {
			return financiadores.saveAndFlush(financiador);
		} catch (DataIntegrityViolationException choque) {
			log.info("Alta de financiador rechazada por unique: codigo={}", codigo);
			if (indiceContiene(choque, "_nombre_")) {
				throw new FinanciadorNombreTakenException();
			}
			if (indiceContiene(choque, "_cuit_")) {
				throw new FinanciadorCuitTakenException();
			}
			throw new FinanciadorCodigoTakenException(codigo);
		}
	}

	/**
	 * Traduce el choque de una EDICION. Solo el nombre y el CUIT pueden chocar; lo que no se
	 * reconoce se deja propagar tal cual — ver el comentario del bloque que llama a este metodo.
	 */
	private static RuntimeException traducirEdicion(
			DataIntegrityViolationException choque, String nombre) {

		if (indiceContiene(choque, "_nombre_")) {
			log.info("Edicion de financiador rechazada por nombre repetido");
			return new FinanciadorNombreTakenException();
		}
		if (indiceContiene(choque, "_cuit_")) {
			log.info("Edicion de financiador rechazada por CUIT repetido");
			return new FinanciadorCuitTakenException();
		}
		return choque;
	}

	private static boolean indiceContiene(DataIntegrityViolationException choque, String fragmento) {
		Throwable causa = choque.getMostSpecificCause();
		String mensaje = causa.getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains(fragmento);
	}

	/**
	 * Los cambios efectivos, para el detalle de la auditoria.
	 *
	 * <p>Solo entra lo que realmente cambia de valor: un PATCH que reenvia el nombre igual no es
	 * un renombre, y registrarlo como tal llenaria la auditoria de ruido que despues nadie sabe
	 * leer. <b>El CUIT viaja como "cambio o no cambio" y no con sus valores</b>: es identidad
	 * fiscal de un tercero y la tabla de auditoria termina en backups.
	 */
	private static Map<String, String> cambios(
			Financiador financiador, String nombre, FinanciadorEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(financiador.getNombre())) {
			detalles.put("nombre", financiador.getNombre() + " -> " + nombre);
		}
		if (command.tipo() != null && command.tipo() != financiador.getTipo()) {
			detalles.put("tipo", financiador.getTipo() + " -> " + command.tipo());
		}
		if (command.cuit() != null) {
			String normalizado = Financiador.normalizarCuit(command.cuit());
			if (normalizado != null && !normalizado.equals(financiador.getCuit())) {
				detalles.put("cuit", "modificado");
			}
		}
		return detalles;
	}

	private void auditar(
			String eventType,
			Financiador financiador,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				financiador.getOrganizationId(),
				// Sin consultorio: el financiador es de la ORGANIZACION. Atribuir el cambio a la
				// sede desde la que se hizo sugeriria que otra sede ve algo distinto, y no es asi.
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_FINANCIADOR,
				financiador.getId(),
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
			throw new IllegalArgumentException(
					"La baja de un financiador exige un motivo declarado");
		}
		return motivo.strip();
	}
}
