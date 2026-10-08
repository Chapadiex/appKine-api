package com.akine.offering.application;

import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.exception.ServicioCodigoTakenException;
import com.akine.offering.domain.exception.ServicioNombreTakenException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.exception.ServicioYaInactivoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El catalogo GLOBAL de Servicios: QUE existe, no como lo presta nadie (M27/M06, regla maestra 14).
 *
 * <h2>La distincion que sostiene esta clase, y donde vive la otra mitad</h2>
 *
 * <p>Un {@link Servicio} es <b>puramente global</b>: una sola poblacion de filas, sin
 * {@code organization_id}, quinta excepcion a ADR-0004 declarada en <b>ADR-0023</b>. COMO un
 * centro concreto presta ese concepto —precio, duracion, cupo, modalidad efectiva— no vive aca:
 * vive en {@code OfertaServicioConsultorio} y lo administra {@code OfertaService}. Esta clase no
 * conoce ninguna oferta, no las lee y no las escribe.
 *
 * <p>Es el mismo problema que resuelve {@code resource.application.CatalogoService} —conviene
 * leer su cabecera antes de tocar esta— con una simplificacion importante: alli hay <b>dos</b>
 * poblaciones, la global y la de cada tenant, y toda la clase se deriva de esa diferencia. Aca
 * solo existe la mitad global, asi que la tabla de aquella cabecera se colapsa a una sola fila:
 *
 * <pre>
 *   QUIEN LO VE      cualquier usuario autenticado del producto
 *   QUIEN LO MUTA    el rol de plataforma, y nadie mas
 *   DE QUE DEPENDE   de nada: no referencia ningun otro concepto
 * </pre>
 *
 * <h2>Autorizacion, y por que las lecturas no piden nada</h2>
 *
 * <p><b>Mutar</b> —crear, editar, dar de baja— exige <b>rol de plataforma, sin ningun permiso de
 * tenant de por medio</b>. Es el mismo tratamiento que recibe el catalogo de planes y que 02.05
 * le dio al catalogo clinico global, y por el mismo motivo: no es dato de un tenant, es la oferta
 * comun. El mecanismo es literalmente el de {@code CatalogoService.exigirPlataforma} —comprobar
 * {@link OperatingActor#platformAdmin()}, que {@code api} llena revalidando {@code platform_role}
 * contra la base en este mismo request— y <b>no se inventa uno nuevo</b>.
 *
 * <p><b>Leer</b> no exige nada mas que estar autenticado, y eso ya lo garantiza el
 * {@code anyRequest().authenticated()} de {@code SecurityConfig}: por eso {@link #buscar} no
 * recibe un {@link OperatingActor}. No es una omision — es que no habria nada que decidir con el.
 * Cualquier usuario del producto necesita ver este catalogo para poder configurar las ofertas de
 * su propio centro, y no hay ninguna fila ajena que ocultar: la poblacion es unica y comun. Donde
 * {@code CatalogoService} arma una lista de {@code owners} visibles a partir del contexto, aca no
 * hay contexto que consultar. <b>Si alguien le agrega a esta clase un filtro por
 * {@code organizationId}</b> —a una firma, a una consulta o al {@code WHERE} de una nativa— es
 * que entendio mal el alcance: lo contextual es la Oferta.
 *
 * <p><b>Y lo que deliberadamente NO se concede:</b> un administrador de plataforma <b>no ve ni
 * muta ninguna Oferta de ningun tenant</b> por esta clase ni por ninguna otra de este modulo. La
 * configuracion comercial de un centro es informacion suya. Es la misma linea que trazo
 * {@code CatalogoService} con los conceptos contextuales y §10.2 de la matriz con los espacios.
 *
 * <p><b>Esta etapa no crea ningun codigo de permiso.</b> La matriz de permisos no la amplia una
 * etapa (matriz §5), mismo criterio que 02.04 y 02.05. {@code offering.domain.PermissionCodes}
 * declara el unico codigo que este modulo evalua —{@code consultorio:manage}, para las Ofertas— y
 * explica por que el rol de plataforma no es uno de ellos: un Servicio es global y no tiene sede
 * que le pertenezca a nadie, asi que la formula del evaluador "permiso con alcance de sede" no
 * tiene sobre que aplicarse.
 *
 * <h2>El hueco heredado que esta clase NO cierra</h2>
 *
 * <p><b>Hoy ningun endpoint le dice al frontend si quien mira tiene rol de plataforma.</b> La
 * pantalla que administre este catalogo va a chocar contra exactamente la misma pared que dejo
 * abierta RF-M06-005 en 02.05, donde resolver una solicitud del catalogo clinico exige rol de
 * plataforma y la consola nunca se pudo construir por este motivo. El backend rechaza
 * correctamente con 403, pero el cliente no tiene forma de saber de antemano si debe ofrecer el
 * boton: la unica salida hoy es intentar la operacion y leer el error. Cerrarlo es una etapa
 * propia —un endpoint que exponga el rol de plataforma del actor— y no se resuelve desde aca. Se
 * deja escrito para que el proximo lector no lo descubra solo.
 *
 * <h2>El caso que rompe el diseno: la baja NO cascadea (§7.8 del diseno de etapa)</h2>
 *
 * <p>Dar de baja un Servicio global mientras hay ofertas activas que lo referencian en varios
 * centros de tenants distintos <b>no toca ninguna de esas ofertas</b>. RF-M27-002 prohibe el
 * borrado fisico cuando existen referencias y RN-M03-006 prohibe afectar historicos: las ofertas
 * vigentes siguen operando exactamente como antes, con su precio, su duracion y su cupo intactos.
 * Lo unico que la baja impide es <b>crear ofertas NUEVAS</b> sobre un servicio inactivo, y esa
 * comprobacion no esta aca sino en el camino de alta de {@code OfertaService}, leyendo
 * {@link Servicio#isOperable()} por el puerto (ruling R1).
 *
 * <p>La garantia no es una promesa de este javadoc: es <b>estructural</b>. Esta clase no recibe
 * {@code OfertaRepositoryPort} en su constructor, {@link Servicio} no tiene ninguna relacion JPA
 * hacia las ofertas y {@code oferta.servicio_id} es un {@code Long} pelado que nunca se
 * desreferencia. No hay por donde cascadear aunque alguien quisiera; para introducir una cascada
 * habria primero que inyectar el puerto de ofertas, y eso es lo que
 * {@code la_baja_no_cascadea_sobre_las_ofertas_que_lo_referencian} vigila.
 *
 * <p><b>Si ademas hay que avisarle a los centros afectados esta deliberadamente sin decidir</b>, y
 * la razon esta en el puerto: saber cuantos son es una consulta cross-tenant —cuenta ofertas de
 * TODAS las organizaciones— que solo el rol de plataforma podria hacer, y
 * {@code OfertaRepositoryPort} no la expone a proposito, para no diluir su invariante de que toda
 * consulta filtra por tenant. No se agrega aca.
 */
@Service
public class ServicioService {

	private static final Logger log = LoggerFactory.getLogger(ServicioService.class);

	private final ServicioRepositoryPort servicios;
	private final AuditTrail auditTrail;

	public ServicioService(ServicioRepositoryPort servicios, AuditTrail auditTrail) {
		this.servicios = servicios;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Busqueda del catalogo global, ordenada por nombre.
	 *
	 * <p>Devuelve los INACTIVOS cuando se piden explicitamente, y con 200: RN-M03-006 exige que
	 * los historicos sigan resolviendo, y responder "no existe" seria borrar historia por la
	 * puerta de atras. Con el filtro por defecto ({@code ACTIVO}) un selector nunca ofrece un
	 * servicio dado de baja, que es lo que evita que el 409 de RF-M27-002 tenga que aparecer en
	 * la pantalla de alta de una Oferta.
	 *
	 * <p><b>Sin {@link OperatingActor}:</b> no hay nada que autorizar mas alla de estar
	 * autenticado, y no hay ninguna fila que acotar. Ver la cabecera de la clase.
	 */
	@Transactional(readOnly = true)
	public List<ServicioView> buscar(ServicioBusqueda filtros) {
		return servicios.buscar(filtros.patron(), filtros.activoFiltro()).stream()
				.map(ServicioView::de)
				.toList();
	}

	// =================================================================================
	// Mutaciones — rol de plataforma
	// =================================================================================

	/**
	 * Alta de un Servicio en el catalogo global (RF-M27-001).
	 *
	 * <p><b>No consulta si el codigo existe antes de insertar</b>, y es deliberado: entre un
	 * SELECT de comprobacion y el INSERT hay una ventana en la que otro request entra. La unica
	 * comprobacion sin ventana es el unique {@code uk_servicio_codigo_vigente}, asi que el 409 se
	 * produce traduciendo su violacion. El efecto de lado que importa, y que un pre-chequeo
	 * romperia: un codigo o un nombre liberado por una baja logica <b>se puede reusar</b>, porque
	 * el unique lleva {@code deleted_key} como discriminador y nada en este metodo lo impide.
	 *
	 * <p>Tampoco lleva {@code Idempotency-Key}: ver {@link ServicioAltaCommand}.
	 */
	@Transactional
	public ServicioView crear(OperatingActor actor, ServicioAltaCommand command) {
		exigirPlataforma(actor, "crear un servicio del catalogo global");

		String codigo = normalizar(command.codigo(), "El codigo del servicio es obligatorio");
		String nombre = normalizar(command.nombre(), "El nombre del servicio es obligatorio");

		Servicio servicio = new Servicio(
				codigo,
				nombre,
				command.descripcion(),
				command.naturaleza(),
				command.modalidadDefault(),
				// Ausente se toma como false: es el valor menos invasivo, y el que no le impone a
				// una Oferta futura una obligacion clinica que nadie declaro (RN-M06-005).
				Boolean.TRUE.equals(command.requiereCasoClinicoDefault()),
				Boolean.TRUE.equals(command.generaRegistroClinicoDefault()));

		Servicio creado = persistir(servicio, codigo, nombre);

		Instant ahora = Instant.now();
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("codigo", codigo);
		detalles.put("naturaleza", creado.getNaturaleza().name());
		detalles.put("modalidadDefault", creado.getModalidadDefault().name());
		auditar(AuditEvents.SERVICIO_CREATED, creado, actor, null, "ACTIVO", null, detalles, ahora);

		log.info("Servicio del catalogo global creado: servicioId={} codigo={}",
				creado.getId(), codigo);

		return ServicioView.de(creado);
	}

	/**
	 * Edicion parcial (RF-M27-001). El codigo no se toca: ver {@link ServicioEdicionCommand}.
	 *
	 * <p>Un servicio INACTIVO no se edita: 409. Reabrir la ficha de algo dado de baja para
	 * cambiarle el nombre reescribiria el historico que RN-M03-006 protege — y no existe la
	 * reactivacion, ver {@link ServicioYaInactivoException}.
	 */
	@Transactional
	public ServicioView editar(
			OperatingActor actor, long servicioId, ServicioEdicionCommand command) {

		exigirPlataforma(actor, "editar un servicio del catalogo global");

		Servicio servicio = cargar(servicioId);
		exigirOperable(servicio, "editar");
		exigirVersion(servicio, command.expectedVersion());

		String nombre = command.nombre() == null
				? null
				: normalizar(command.nombre(), "El nombre del servicio es obligatorio");
		Map<String, String> detalles = cambios(servicio, nombre, command);

		servicio.updateDatos(
				nombre,
				command.descripcion(),
				command.naturaleza(),
				command.modalidadDefault(),
				command.requiereCasoClinicoDefault(),
				command.generaRegistroClinicoDefault());

		Servicio guardado;
		try {
			guardado = servicios.saveAndFlush(servicio);
		} catch (DataIntegrityViolationException choque) {
			// El unico UNIQUE que una edicion puede violar es el de nombre —el codigo es
			// updatable = false y ni siquiera viaja en el comando—, pero
			// DataIntegrityViolationException NO la levanta solo un unique: un valor demasiado
			// largo para su columna, bajo el modo estricto de MySQL, llega por la misma puerta.
			// Traducir el bloque entero a "nombre repetido" produciria un 409 que MIENTE: un PATCH
			// que solo manda una descripcion de 3000 caracteres —sin tocar el nombre— responderia
			// "ya existe un servicio vigente con ese nombre" con nombre = null, cuando es un 400.
			// Por eso se discrimina con la misma senal que usa persistir, y lo que no reconoce se
			// deja propagar: un 500 honesto es mejor que un 409 inventado.
			if (esConflictoDeNombre(choque)) {
				log.info("Edicion de servicio rechazada por nombre repetido: servicioId={}",
						servicioId);
				throw new ServicioNombreTakenException(nombre);
			}
			throw choque;
		}

		Instant ahora = Instant.now();
		auditar(AuditEvents.SERVICIO_UPDATED, guardado, actor, null, null, null, detalles, ahora);

		return ServicioView.de(guardado);
	}

	/**
	 * Baja logica con motivo obligatorio (RF-M27-002).
	 *
	 * <p>NO borra nada: el servicio queda INACTIVO, sigue siendo legible y conserva su nombre y su
	 * codigo. Libera ese codigo y ese nombre para un servicio nuevo —el unique lleva
	 * {@code deleted_key}— y deja de ofrecerse en los selectores.
	 *
	 * <p><b>Y NO cascadea sobre las ofertas que lo referencian.</b> Es el caso que rompe el diseno
	 * (§7.8) y esta desarrollado en la cabecera de la clase: las ofertas vigentes de cualquier
	 * centro siguen operando sin un solo cambio, y lo que se impide es crear ofertas nuevas, del
	 * lado de {@code OfertaService}. Este metodo no tiene forma de tocar una oferta: esta clase no
	 * recibe el puerto de ofertas.
	 */
	@Transactional
	public ServicioView darDeBaja(OperatingActor actor, long servicioId, String motivo) {
		exigirPlataforma(actor, "dar de baja un servicio del catalogo global");

		Servicio servicio = cargar(servicioId);
		exigirOperable(servicio, "dar de baja");

		Instant ahora = Instant.now();
		servicio.deactivate(ahora, exigirMotivo(motivo));
		Servicio guardado = servicios.save(servicio);

		auditar(AuditEvents.SERVICIO_DEACTIVATED, guardado, actor,
				"ACTIVO", "INACTIVO", motivo, Map.of(), ahora);

		log.info("Servicio del catalogo global dado de baja: servicioId={}", servicioId);
		return ServicioView.de(guardado);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * El unico control de autorizacion de esta clase.
	 *
	 * <p>403 y jamas 401: el interceptor del frontend borra el token ante cualquier 401 y el
	 * usuario entra en un bucle de login del que no sale. Tampoco 404: el servicio es global y
	 * visible para todos, asi que ocultar su existencia no protegeria nada y confundiria al
	 * cliente, que acaba de listarlo. El 404 anti-enumeracion de ADR-0018 protege filas de otros
	 * tenants, y aca no hay ninguna.
	 *
	 * <p>El rechazo <b>no se audita desde aca</b>: una excepcion de negocio hace rollback de todo
	 * lo escrito antes de lanzarla, incluida la auditoria. Ver {@link AuditEvents}.
	 */
	private static void exigirPlataforma(OperatingActor actor, String operacion) {
		if (!actor.platformAdmin()) {
			log.info("Mutacion del catalogo global de servicios rechazada: accountId={} operacion={}",
					actor.accountId(), operacion);
			throw new AccessDeniedException(
					"El catalogo global de servicios lo administra unicamente la plataforma");
		}
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	private Servicio cargar(long servicioId) {
		return servicios.findById(servicioId)
				.orElseThrow(() -> new ServicioNotAccessibleException(servicioId));
	}

	private static void exigirOperable(Servicio servicio, String operacion) {
		if (!servicio.isOperable()) {
			log.info("Operacion sobre servicio inactivo rechazada: servicioId={} operacion={}",
					servicio.getId(), operacion);
			throw new ServicioYaInactivoException(servicio.getId(), operacion);
		}
	}

	private static void exigirVersion(Servicio servicio, long esperada) {
		if (servicio.getVersion() != esperada) {
			// GlobalExceptionHandler lo mapea a 409 concurrent-modification (DP-21), igual que la
			// subclase de JPA.
			throw new OptimisticLockingFailureException(
					"El servicio fue modificado por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/**
	 * Guarda forzando el flush y traduce el choque de unique.
	 *
	 * <p><b>Como se distingue un choque de codigo de uno de nombre.</b> MySQL nombra el indice
	 * violado en el mensaje del error, y ese nombre es la unica senal disponible: la excepcion de
	 * Spring no lleva ningun campo estructurado con la constraint. Se busca {@code _nombre_}
	 * —por {@code uk_servicio_nombre_vigente}— y, si no aparece, se responde como conflicto de
	 * codigo. La degradacion es benigna: los dos son 409 con {@code type} distinto, y el peor caso
	 * es un mensaje que nombra el campo equivocado, nunca un dato mal guardado.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA</b> —ni una lectura para
	 * averiguar cual de los dos choco—. La especificacion lo prohibe y lo que sale de ahi es un 500
	 * en vez de un 409 legitimo. Mismo patron, y misma trampa, que {@code CatalogoService.persistir}.
	 */
	private Servicio persistir(Servicio servicio, String codigo, String nombre) {
		try {
			return servicios.saveAndFlush(servicio);
		} catch (DataIntegrityViolationException choque) {
			log.info("Alta de servicio rechazada por unique: codigo={}", codigo);
			if (esConflictoDeNombre(choque)) {
				throw new ServicioNombreTakenException(nombre);
			}
			throw new ServicioCodigoTakenException(codigo);
		}
	}

	private static boolean esConflictoDeNombre(DataIntegrityViolationException choque) {
		Throwable causa = choque.getMostSpecificCause();
		String mensaje = causa.getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains("_nombre_");
	}

	/**
	 * Los cambios efectivos, para el detalle de la auditoria.
	 *
	 * <p>Solo entra lo que realmente cambia de valor: un PATCH que reenvia el nombre igual no es
	 * un renombre y registrarlo como tal llenaria la auditoria de ruido que despues nadie sabe
	 * leer. Mismo criterio que {@code CatalogoService.cambios}.
	 */
	private static Map<String, String> cambios(
			Servicio servicio, String nombre, ServicioEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(servicio.getNombre())) {
			detalles.put("nombre", servicio.getNombre() + " -> " + nombre);
		}
		if (command.naturaleza() != null && command.naturaleza() != servicio.getNaturaleza()) {
			detalles.put("naturaleza", servicio.getNaturaleza() + " -> " + command.naturaleza());
		}
		if (command.modalidadDefault() != null
				&& command.modalidadDefault() != servicio.getModalidadDefault()) {
			detalles.put("modalidadDefault",
					servicio.getModalidadDefault() + " -> " + command.modalidadDefault());
		}
		if (command.requiereCasoClinicoDefault() != null
				&& command.requiereCasoClinicoDefault() != servicio.isRequiereCasoClinicoDefault()) {
			detalles.put("requiereCasoClinicoDefault",
					servicio.isRequiereCasoClinicoDefault() + " -> "
							+ command.requiereCasoClinicoDefault());
		}
		if (command.generaRegistroClinicoDefault() != null
				&& command.generaRegistroClinicoDefault()
						!= servicio.isGeneraRegistroClinicoDefault()) {
			detalles.put("generaRegistroClinicoDefault",
					servicio.isGeneraRegistroClinicoDefault() + " -> "
							+ command.generaRegistroClinicoDefault());
		}
		return detalles;
	}

	private void auditar(
			String eventType,
			Servicio servicio,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				// organizationId y consultorioId NULL SIEMPRE, y no "los del actor": un Servicio es
				// global y no es dato de ningun tenant. Es un evento de plataforma sin organizacion,
				// la forma que ADR-0019 ya admite para audit_event. Poner aca el contexto de quien
				// ejecuta le atribuiria a un centro un cambio del catalogo comun, y ademas el actor
				// tipico —un administrador de plataforma— no tiene contexto que poner.
				null,
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_SERVICIO,
				servicio.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	/**
	 * Normaliza lo que la collation no puede: espacios al principio, al final y dobles espacios
	 * internos.
	 *
	 * <p>Mayusculas y acentos NO se tocan aca: eso lo resuelve {@code utf8mb4_0900_ai_ci} tanto
	 * para el unique de duplicados como para el {@code LIKE} de la busqueda. Normalizarlos ademas
	 * en Java seria una segunda definicion de "igual" que tarde o temprano diverge de la del motor,
	 * y guardaria el nombre distinto de como lo escribio el usuario.
	 */
	private static String normalizar(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip().replaceAll("\\s{2,}", " ");
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de un servicio: sin el, la auditoria "
							+ "no responde por que seis meses despues");
		}
		return motivo.strip();
	}
}
