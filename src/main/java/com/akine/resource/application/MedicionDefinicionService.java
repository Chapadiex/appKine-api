package com.akine.resource.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.domain.MedicionDefinicion;
import com.akine.resource.domain.exception.CatalogoCodeTakenException;
import com.akine.resource.domain.exception.CatalogoNameTakenException;
import com.akine.resource.domain.exception.MedicionDefinicionInactivaException;
import com.akine.resource.domain.exception.MedicionDefinicionNoAccesibleException;
import com.akine.resource.domain.port.MedicionDefinicionRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;

/**
 * Catalogo de definiciones de medicion: que se mide, en que unidad y con que rango
 * (M06, al servicio de RF-M14-004).
 *
 * <h2>Por que este catalogo vive en {@code resource} y no en {@code encounter}</h2>
 *
 * <p>La intuicion lo pone en {@code encounter} —"es parte de la evaluacion"— y no lo es. Una
 * <b>definicion</b> de medida es un catalogo: vive mas que cualquier sesion, la comparten todas
 * las especialidades y la plataforma siembra las universales. Ponerlo en {@code encounter}
 * dejaria dos modulos dueños de la misma clase de cosa, y a {@code resource} —dueño de
 * {@code especialidad}, {@code practica}, {@code nomenclador} y {@code nomenclador_item}— sin la
 * mitad de su catalogo.
 *
 * <p>{@code encounter} <b>no escribe</b> este catalogo: lo valida por {@code resource.spi}. Y
 * este modulo <b>no sabe</b> que existen mediciones tomadas — un catalogo que conociera a sus
 * consumidores dejaria de ser un catalogo.
 *
 * <h2>Autorizacion: la misma politica interina de 02.05</h2>
 *
 * <p>Sin permisos nuevos. M06 necesita {@code catalogo:read} y {@code catalogo:manage}, que el
 * catalogo de la matriz todavia no tiene, y <b>esta etapa no los inventa</b> (matriz §5 es
 * vinculante; la propuesta esta en §11.1). Mientras tanto, exactamente como
 * {@code CatalogoService}: lecturas por <b>pertenencia</b>, mutaciones contextuales con
 * {@code consultorio:manage}, mutaciones globales con rol de plataforma. La politica vive en
 * {@link AlcanceDelCatalogo} y no se reescribe aca.
 *
 * <h2>La baja NO cascadea, y es el punto de la etapa</h2>
 *
 * <p>Dar de baja un test discontinuado deja <b>intactas</b> las mediciones que ya lo usaban: se
 * siguen leyendo y se siguen comparando. Lo unico que se impide es registrar nuevas, con 409
 * {@code medicion-definicion-inactiva}. Es exactamente la regla que 02.06 fijo para la baja de un
 * servicio global, y la unica compatible con RN-M06-001 y RN-M06-002.
 *
 * <p>Que el significado sobreviva a la baja <b>no depende de esta clase</b>: depende de que
 * {@code sesion_medicion} copie la unidad, el nombre y la version en su fila. Sin ese snapshot,
 * un {@code UPDATE} de aca reescribiria el pasado sin tocar aquella tabla.
 *
 * <h2>Codigos</h2>
 *
 * <ul>
 *   <li><b>404</b> para una definicion de otro tenant o inexistente. Un 403 confirmaria que ese
 *       id existe y bastaria recorrer numeros para censar que tests tiene cada centro (ADR-0018).</li>
 *   <li><b>403</b> cuando el actor la ve y le falta el permiso, o cuando no eligio contexto.
 *       Nunca 401.</li>
 *   <li><b>409</b> para los invariantes de estado: codigo o nombre repetidos, definicion ya
 *       inactiva, version desactualizada.</li>
 * </ul>
 *
 * <p>Una definicion INACTIVA se lee con <b>200</b>, no con 404: responder "no existe" justo cuando
 * alguien quiere entender una medicion vieja seria borrar historia por la puerta de atras.
 */
@Service
public class MedicionDefinicionService {

	private static final Logger log = LoggerFactory.getLogger(MedicionDefinicionService.class);

	private final MedicionDefinicionRepositoryPort definiciones;
	private final AlcanceDelCatalogo alcances;
	private final AuditTrail auditTrail;

	public MedicionDefinicionService(
			MedicionDefinicionRepositoryPort definiciones,
			AlcanceDelCatalogo alcances,
			AuditTrail auditTrail) {

		this.definiciones = definiciones;
		this.alcances = alcances;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Listado del catalogo: lo global y lo propio en la misma lista.
	 *
	 * <p>Es como se elige un test en la realidad, y con {@code estado = ACTIVO} por defecto, que
	 * es lo que hace que un formulario de examen nunca ofrezca una definicion dada de baja sin
	 * que el frontend tenga que acordarse.
	 *
	 * <p>Reusa {@link CatalogoBusqueda} en vez de declarar un filtro propio: son los mismos tres
	 * ejes —texto, estado, alcance— con los mismos defaults, y una segunda copia habria sido una
	 * segunda definicion de "que ve un selector". Su {@code especialidadId} no aplica y se ignora.
	 */
	@Transactional(readOnly = true)
	public List<MedicionDefinicionView> listar(OperatingActor actor, CatalogoBusqueda filtros) {
		List<Long> owners = alcances.ownersSegunFiltro(actor, filtros.alcance());
		return definiciones.buscar(owners, filtros.patron(), filtros.activoFiltro()).stream()
				.map(MedicionDefinicionView::de)
				.toList();
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de una definicion.
	 *
	 * <p>No lleva {@code Idempotency-Key}, igual que el resto de M06 y por el mismo motivo: una
	 * definicion no consume cupo de ningun plan, asi que lo unico que un reintento podria producir
	 * es una fila duplicada, y contra eso el unique de codigo entre las definiciones vigentes del
	 * mismo duenio es una garantia mas fuerte que una clave que depende de que el cliente la mande
	 * bien. El reintento responde 409 y no crea nada.
	 */
	@Transactional
	public MedicionDefinicionView crear(
			OperatingActor actor, MedicionDefinicionAltaCommand command) {

		CatalogoAlcance alcance = command.alcance() == null
				? CatalogoAlcance.ORGANIZACION
				: command.alcance();
		Long duenio = alcances.exigirGestionDe(actor, alcance);

		String codigo = normalizar(command.codigo(), "codigo");
		String nombre = normalizar(command.name(), "name");

		MedicionDefinicion definicion = new MedicionDefinicion(
				duenio, codigo, nombre, command.descripcion(),
				command.tipo(), command.unidad(), command.minimo(), command.maximo());

		MedicionDefinicion creada;
		try {
			creada = definiciones.saveAndFlush(definicion);
		} catch (DataIntegrityViolationException choque) {
			// Despues de un flush fallido NO se vuelve a tocar la sesion JPA: ni una lectura para
			// averiguar cual de los dos uniques choco. La especificacion lo prohibe y lo que sale
			// de ahi es un 500 en vez del 409 legitimo.
			log.info("Alta de definicion de medicion rechazada por unique: alcance={} codigo={}",
					alcance, codigo);
			boolean global = alcance == CatalogoAlcance.GLOBAL;
			if (esConflictoDeNombre(choque)) {
				throw new CatalogoNameTakenException(nombre, global);
			}
			throw new CatalogoCodeTakenException(codigo, global);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("alcance", alcance.name());
		detalles.put("codigo", codigo);
		detalles.put("tipo", String.valueOf(command.tipo()));
		auditar(AuditEvents.MEDICION_DEFINICION_CREATED, creada, actor,
				null, "ACTIVO", null, detalles);

		log.info("Definicion de medicion creada: alcance={} definicionId={} tipo={}",
				alcance, creada.getId(), creada.getTipo());

		return MedicionDefinicionView.de(creada);
	}

	/**
	 * Edicion parcial.
	 *
	 * <p>{@code READ_COMMITTED} como toda mutacion del modulo que compara un estado leido: bajo el
	 * {@code REPEATABLE READ} de MySQL la lectura del evaluador de permisos fijaria el snapshot y
	 * el control de version compararia contra datos anteriores al commit del competidor.
	 *
	 * <p><b>Editar el rango no revalida nada hacia atras.</b> Estrecharlo no vuelve invalidas las
	 * mediciones ya tomadas: fueron validas contra la version vigente en su momento, y esa version
	 * quedo copiada en su fila.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MedicionDefinicionView editar(
			OperatingActor actor, long definicionId, MedicionDefinicionEdicionCommand command) {

		MedicionDefinicion definicion = cargar(actor, definicionId);
		alcances.exigirGestionSobre(actor, definicion.getOrganizationId());
		exigirOperable(definicion);
		exigirVersion(definicion, command.expectedVersion());

		String nombre = command.name() == null ? null : normalizar(command.name(), "name");
		Map<String, String> detalles = cambios(definicion, nombre, command);

		definicion.updateDatos(
				nombre, command.descripcion(), command.unidad(),
				command.minimo(), command.maximo(), command.clearRango());

		MedicionDefinicion guardada;
		try {
			guardada = definiciones.saveAndFlush(definicion);
		} catch (DataIntegrityViolationException choque) {
			log.info("Edicion de definicion de medicion rechazada por nombre repetido: "
					+ "definicionId={}", definicionId);
			throw new CatalogoNameTakenException(nombre, definicion.esGlobal());
		}

		auditar(AuditEvents.MEDICION_DEFINICION_UPDATED, guardada, actor,
				null, null, null, detalles);
		return MedicionDefinicionView.de(guardada);
	}

	/**
	 * Baja logica con motivo obligatorio (RN-M06-001).
	 *
	 * <p><b>No cascadea y no borra nada</b>: las mediciones existentes siguen legibles y
	 * comparables, y lo unico que se impide es registrar nuevas con esta definicion. No se
	 * comprueba ninguna "referencia vigente" —al reves que la baja de una especialidad, que si
	 * bloquea si le cuelgan practicas activas— porque una medicion tomada no es algo que quede
	 * colgando: es un hecho ocurrido, y RN-M06-001 pide que sobreviva a la baja, no que la impida.
	 *
	 * <p><b>No hay reactivacion.</b> Una definicion que vuelve es una definicion nueva; modelarlo
	 * como baja deshecha borraria el rastro de que el test dejo de usarse alguna vez.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MedicionDefinicionView darDeBaja(
			OperatingActor actor, long definicionId, String motivo) {

		MedicionDefinicion definicion = cargar(actor, definicionId);
		alcances.exigirGestionSobre(actor, definicion.getOrganizationId());
		exigirOperable(definicion);

		definicion.deactivate(Instant.now(), exigirMotivo(motivo));
		MedicionDefinicion guardada = definiciones.save(definicion);

		auditar(AuditEvents.MEDICION_DEFINICION_DEACTIVATED, guardada, actor,
				"ACTIVO", "INACTIVO", motivo, Map.of());

		log.info("Definicion de medicion dada de baja: definicionId={}", definicionId);
		return MedicionDefinicionView.de(guardada);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private MedicionDefinicion cargar(OperatingActor actor, long definicionId) {
		return definiciones.findVisible(definicionId, alcances.ownersVisibles(actor))
				.orElseThrow(() -> new MedicionDefinicionNoAccesibleException(definicionId));
	}

	private static void exigirOperable(MedicionDefinicion definicion) {
		if (!definicion.isOperable()) {
			throw new MedicionDefinicionInactivaException(definicion.getId());
		}
	}

	/**
	 * El control optimista, en un solo lugar.
	 *
	 * <p>Se lanza el mismo tipo que JPA usaria para que el handler lo mapee igual y el cliente vea
	 * un solo comportamiento; la diferencia es que aca se detecta <b>antes</b> de escribir.
	 */
	private static void exigirVersion(MedicionDefinicion definicion, long esperada) {
		if (definicion.getVersion() != esperada) {
			throw new OptimisticLockingFailureException(
					"La definicion de medicion " + definicion.getId() + " cambio desde que se "
							+ "leyo: version " + esperada + " contra " + definicion.getVersion());
		}
	}

	/**
	 * Como se distingue un choque de codigo de uno de nombre.
	 *
	 * <p>MySQL nombra el indice violado en el mensaje del error, y ese nombre es la unica senal
	 * disponible: la excepcion de Spring no lleva ningun campo estructurado con la constraint. La
	 * degradacion es benigna —los dos son 409 con {@code type} distinto, y el peor caso es un
	 * mensaje que nombra el campo equivocado, nunca un dato mal guardado—.
	 */
	private static boolean esConflictoDeNombre(DataIntegrityViolationException choque) {
		Throwable causa = choque.getMostSpecificCause();
		String mensaje = causa.getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains("_name_");
	}

	private static Map<String, String> cambios(
			MedicionDefinicion definicion, String nombre, MedicionDefinicionEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(definicion.getName())) {
			detalles.put("name", definicion.getName() + " -> " + nombre);
		}
		// La unidad se registra SIEMPRE que cambie, y es el detalle que mas importa de esta
		// auditoria: es el unico campo cuyo cambio alteraria el significado de las mediciones
		// pasadas si no estuviera congelado en cada fila.
		if (command.unidad() != null && !command.unidad().equals(definicion.getUnidad())) {
			detalles.put("unidad", definicion.getUnidad() + " -> " + command.unidad());
		}
		if (command.clearRango()) {
			detalles.put("rango", "(sin rango)");
		} else {
			if (command.minimo() != null && !command.minimo().equals(definicion.getMinimo())) {
				detalles.put("minimo", definicion.getMinimo() + " -> " + command.minimo());
			}
			if (command.maximo() != null && !command.maximo().equals(definicion.getMaximo())) {
				detalles.put("maximo", definicion.getMaximo() + " -> " + command.maximo());
			}
		}
		return detalles;
	}

	@SuppressWarnings("java:S107")
	private void auditar(
			String eventType,
			MedicionDefinicion definicion,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details) {

		auditTrail.record(new AuditEntry(
				// NULL cuando la definicion es global: es un evento de plataforma sin tenant, la
				// forma que ADR-0019 ya admite para audit_event.
				definicion.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_MEDICION_DEFINICION,
				definicion.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				Instant.now()));
	}

	/**
	 * Normaliza lo que la collation no puede: espacios al principio, al final y dobles internos.
	 *
	 * <p>Mayusculas y acentos NO se tocan: eso lo resuelve {@code utf8mb4_0900_ai_ci} tanto para
	 * el unique como para el {@code LIKE}. Normalizarlos ademas en Java seria una segunda
	 * definicion de "igual" que tarde o temprano diverge de la del motor.
	 */
	private static String normalizar(String valor, String campo) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("El campo " + campo + " es obligatorio");
		}
		return valor.strip().replaceAll("\\s{2,}", " ");
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de una definicion de medicion: sin "
							+ "el, la auditoria no responde por que seis meses despues");
		}
		return motivo.strip();
	}
}
