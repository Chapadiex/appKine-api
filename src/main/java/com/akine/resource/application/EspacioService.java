package com.akine.resource.application;

import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.Espacio;
import com.akine.resource.domain.EspacioTipo;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.EspacioCapacityBelowOccupancyException;
import com.akine.resource.domain.exception.EspacioHasActiveReferencesException;
import com.akine.resource.domain.exception.EspacioInactiveException;
import com.akine.resource.domain.exception.EspacioNameTakenException;
import com.akine.resource.domain.exception.EspacioNotAccessibleException;
import com.akine.resource.domain.port.EspacioRepositoryPort;
import com.akine.resource.spi.EspacioOccupancyProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Administracion de espacios fisicos de una sede: alta, lectura, edicion, baja logica y
 * consulta base de disponibilidad (M04).
 *
 * <h2>Las dos autorizaciones de este modulo, y por que son distintas</h2>
 *
 * <table>
 *   <caption>Autorizacion por tipo de operacion</caption>
 *   <tr><th>Operacion</th><th>Que se exige</th><th>Quien pasa</th></tr>
 *   <tr>
 *     <td>Mutaciones (alta, edicion, baja)</td>
 *     <td>{@code consultorio:manage} <b>con la sede como alcance</b></td>
 *     <td>{@code ORG_ADMIN} sobre cualquier sede suya, {@code CONSULTORIO_ADMIN} sobre la suya</td>
 *   </tr>
 *   <tr>
 *     <td>Lecturas (detalle, listado, disponibilidad)</td>
 *     <td>{@code espacio:read} <b>con la sede como alcance</b>, sobre pertenencia ya
 *         comprobada</td>
 *     <td>Todo rol de la sede salvo {@code PACIENTE}: incluye {@code PROFESIONAL} y
 *         {@code ADMINISTRATIVO}</td>
 *   </tr>
 * </table>
 *
 * <p><b>Sobre {@code espacio:read}.</b> Cuando 02.02 se construyo, el catalogo de la matriz
 * seccion 5 <b>no tenia ningun codigo de lectura de espacios</b> —el unico aplicable era
 * {@code consultorio:manage}, que la seccion 6 le niega justamente a los dos roles que tienen
 * que poder consultar—, asi que las lecturas autorizaban por <b>pertenencia</b> y el codigo
 * quedaba PROPUESTO en la seccion 10.1. <b>Se aprobo el 25/08/2026</b>: la fila esta en la
 * seccion 5 y en la 6, y estas tres lecturas la exigen.
 *
 * <p><b>El orden de las dos comprobaciones no es cosmetico.</b> Primero pertenencia, despues
 * permiso. Un tenant ajeno tiene que salir por 404 —un 403 confirmaria que esa organizacion
 * existe y bastaria recorrer ids—, y el evaluador de permisos responde 403. Dentro del propio
 * tenant, en cambio, quien decide es el permiso: ahi 403 no filtra nada que el actor no sepa.
 *
 * <h2>Bloqueos: que se bloquea y que deliberadamente NO</h2>
 *
 * <p><b>Ninguna operacion de espacios bloquea {@code subscription}.</b> El orden de bloqueo
 * unico del sistema —{@code subscription} y despues {@code organization}— aplica a las
 * operaciones que consumen o liberan cupo de plan, y ninguna de estas lo hace: <b>no existe
 * ningun {@code LimitCode} de espacios y esta etapa deliberadamente no crea uno</b>, porque
 * ningun RF de M04 declara un tope por plan e inventarlo bloquearia en silencio a los tenants
 * que ya existen. Tomar el lock "por las dudas" tampoco es gratis: serializaria toda la
 * administracion de un centro contra una fila que la operacion no lee ni escribe.
 *
 * <p>Lo que si se bloquea es <b>la fila del propio espacio, con {@code FOR UPDATE}</b>, en las
 * dos mutaciones que deciden contra un conteo externo. Ver {@link #update} y
 * {@link #deactivate}.
 *
 * <p>La suspension de la suscripcion tampoco se comprueba aca, y no es un hueco:
 * {@code TenantContextFilter} rechaza con 409 {@code subscription-suspended} toda mutacion bajo
 * {@code /api/v1/organizations/} que no sea de administracion de suscripcion, antes de que el
 * request llegue a ningun controller. Duplicarlo daria dos reglas que se olvidan por separado.
 *
 * <h2>Auditoria</h2>
 *
 * <p>Se escribe DENTRO de la transaccion del negocio, nunca en un listener post-commit: uno que
 * falla deja la mutacion sin rastro. Y el corolario incomodo: <b>una excepcion de negocio hace
 * rollback de todo lo escrito antes de lanzarla, incluida la auditoria</b>. Por eso ningun
 * rechazo se audita desde aca —el permiso denegado lo registra el evaluador de
 * {@code organization} en su propia transaccion— y por eso el uso de soporte en una LECTURA va
 * por {@link SupportAccessAuditor}, que abre la suya.
 */
@Service
public class EspacioService {

	private static final Logger log = LoggerFactory.getLogger(EspacioService.class);

	private final EspacioRepositoryPort espacioRepository;
	private final ConsultorioDirectory consultorioDirectory;
	private final AccountContextDirectory accountContextDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final SupportAccessAuditor supportAccessAuditor;
	private final List<EspacioOccupancyProbe> occupancyProbes;

	public EspacioService(
			EspacioRepositoryPort espacioRepository,
			ConsultorioDirectory consultorioDirectory,
			AccountContextDirectory accountContextDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SupportAccessAuditor supportAccessAuditor,
			List<EspacioOccupancyProbe> occupancyProbes) {

		this.espacioRepository = espacioRepository;
		this.consultorioDirectory = consultorioDirectory;
		this.accountContextDirectory = accountContextDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
		this.occupancyProbes = occupancyProbes;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Un espacio por id, <b>activo o no</b>.
	 *
	 * <p>Devolver tambien los dados de baja es RN-M04-003: los historicos conservan su nombre y
	 * su estado. Un 404 sobre un espacio dado de baja estaria borrando historia por la puerta de
	 * atras, y ademas dejaria a la pantalla sin poder explicar por que ese nombre no se puede
	 * reusar.
	 *
	 * @throws EspacioNotAccessibleException si no existe, es de otro tenant o de otra sede (404)
	 */
	@Transactional(readOnly = true)
	public EspacioView find(
			OperatingActor actor, long organizationId, long consultorioId, long espacioId) {

		exigirLectura(actor, organizationId, consultorioId);
		Instant ahora = Instant.now();
		return EspacioView.de(cargar(organizationId, consultorioId, espacioId), ahora);
	}

	/**
	 * Los espacios de la sede segun el filtro de estado.
	 *
	 * <p>El default {@code ACTIVO} es lo que cumple el criterio de aceptacion de la etapa: las
	 * selecciones nuevas excluyen los inactivos. Los dados de baja hay que pedirlos.
	 */
	@Transactional(readOnly = true)
	public List<EspacioView> list(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			EspacioEstadoFiltro estado) {

		exigirLectura(actor, organizationId, consultorioId);
		Instant ahora = Instant.now();

		List<Espacio> espacios = switch (estado) {
			case ACTIVO -> espacioRepository
					.findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNameAsc(
							organizationId, consultorioId, true);
			case INACTIVO -> espacioRepository
					.findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNameAsc(
							organizationId, consultorioId, false);
			case TODOS -> espacioRepository
					.findAllByOrganizationIdAndConsultorioIdOrderByNameAsc(
							organizationId, consultorioId);
		};

		return espacios.stream().map(espacio -> EspacioView.de(espacio, ahora)).toList();
	}

	/**
	 * Consulta base de disponibilidad de la sede para una ventana (RF-M04-003).
	 *
	 * <p><b>Responde si el recurso esta EN SERVICIO, no si esta libre de reservas.</b> La
	 * distincion, y por que no puede ser de otra manera todavia, esta en
	 * {@link DisponibilidadView}. {@code lugaresComprometidos} es el maximo de los picos que
	 * declaran las implementaciones de {@link EspacioOccupancyProbe} desde {@code desde} en
	 * adelante; desde el paquete E-1 la unica es la de turnos de {@code scheduling}.
	 *
	 * <p>El filtro por vigencia y por estado ocurre en la BASE y no en memoria: es la consulta
	 * que la agenda de F5 va a ejecutar por cada franja, y traer el catalogo entero para
	 * descartarlo en Java no escala.
	 *
	 * @throws IllegalArgumentException si la ventana no es coherente (400)
	 */
	@Transactional(readOnly = true)
	public List<DisponibilidadView> disponibilidad(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			Instant desde,
			Instant hasta) {

		exigirLectura(actor, organizationId, consultorioId);
		exigirVentana(desde, hasta);

		return espacioRepository.findEnServicio(organizationId, consultorioId, desde, hasta)
				.stream()
				.map(espacio -> disponibilidadDe(espacio, organizationId, desde, hasta))
				.toList();
	}

	// =================================================================================
	// Alta (RF-M04-001, RF-M04-007)
	// =================================================================================

	/**
	 * Da de alta un espacio en la sede.
	 *
	 * <h2>Idempotencia: la garantiza el unique, y por que alcanza aca</h2>
	 *
	 * <p>El alta de una SEDE lleva {@code Idempotency-Key} y una tabla de altas porque consume
	 * cupo irreversible de un limite de plan: un reintento por timeout de red que crea una
	 * segunda sede le gasta al cliente algo que pago. <b>Un espacio no consume nada.</b> Lo que
	 * un reintento podria producir es una fila duplicada, y contra eso el unique
	 * {@code uk_espacio_sede_name_vigente} es una garantia mas fuerte que una clave: no depende
	 * de que el cliente la mande ni de que la reuse bien. El reintento responde 409
	 * {@code espacio-name-taken} y <b>no crea nada</b>, que es lo que CA-M04-001-05 pide
	 * —"reintentos no duplican efectos"—.
	 *
	 * <p>Lo que esta decision cuesta, dicho y no tapado: el cliente que reintenta tras un
	 * timeout recibe un 409 en vez del recurso creado, y tiene que releer el listado para saber
	 * si el alta original entro. Es peor UX y es una diferencia deliberada con el alta de sede.
	 * Si se decide unificar, el camino es agregar la tabla {@code espacio_alta} igual que
	 * {@code consultorio_alta}, sin tocar el resto de este metodo.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws ConsultorioNotOperableException si la sede esta dada de baja (409)
	 * @throws EspacioNameTakenException si ya hay un espacio vigente con ese nombre (409)
	 */
	@Transactional
	public EspacioView create(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			EspacioAltaCommand command) {

		PermissionDecision decision = exigirGestion(actor, organizationId, consultorioId);

		// La sede se valida DESPUES del permiso y contra la base: la FK de espacio garantiza que
		// el id exista, pero no que sea del tenant del request. Sin esta consulta, un
		// consultorioId ajeno inyectado en la URL crearia una fila con el organization_id del
		// atacante y el consultorio_id de la victima, y la FK la aceptaria sin objetar nada.
		ConsultorioSnapshot sede = exigirSedeOperable(organizationId, consultorioId);

		EspacioView creado = persistirAlta(organizationId, sede.id(), actor.accountId(), command);
		auditarSoporte(decision, organizationId, consultorioId, actor, creado.id(), Instant.now());
		return creado;
	}

	/**
	 * Crea el primer box de una sede que se esta dando de alta, dentro de la transaccion del
	 * alta (A-8, CA-M03-002).
	 *
	 * <p><b>No evalua permisos ni relee la sede, y no es un descuido.</b> Lo llama
	 * {@code organization} a traves de {@code organization.spi.AltaDeSedeExtension}, despues de
	 * exigir {@code consultorio:manage} con alcance ORGANIZACION —mas fuerte que el de sede que
	 * pide {@link #create}— en la misma transaccion, y la sede acaba de nacer activa en esa misma
	 * transaccion. El tipo es siempre {@link EspacioTipo#BOX}: es lo que RF-M03-002 nombra.
	 *
	 * <p>Un nombre invalido lanza y revierte el alta entera, sede incluida.
	 *
	 * @throws IllegalArgumentException si el nombre o la capacidad no son validos (400)
	 */
	@Transactional
	public EspacioView crearPrimerBoxDeSedeNueva(
			long organizationId, long consultorioId, long accountId, String nombre, Integer capacidad) {

		return persistirAlta(organizationId, consultorioId, accountId,
				new EspacioAltaCommand(nombre, EspacioTipo.BOX, capacidad, null, null, null));
	}

	private EspacioView persistirAlta(
			long organizationId, long consultorioId, long accountId, EspacioAltaCommand command) {

		Instant ahora = Instant.now();
		String nombre = exigirNombre(command.name());
		EspacioTipo tipo = command.tipo() == null ? EspacioTipo.BOX : command.tipo();
		Instant desde = command.validFrom() == null ? ahora : command.validFrom();

		Espacio espacio = new Espacio(
				organizationId, consultorioId, nombre, tipo,
				exigirCapacidadValida(command.capacidad()), command.notes(),
				desde, command.validUntil());

		Espacio persistido;
		try {
			// saveAndFlush: la clave duplicada tiene que aparecer ACA y no al commit, donde el
			// catch ya no la ve y el advice generico devuelve 500.
			persistido = espacioRepository.saveAndFlush(espacio);
		} catch (DataIntegrityViolationException nombreRepetido) {
			// Y desde aca NO se vuelve a tocar la sesion JPA: ni auditoria, ni lecturas. Una
			// sesion reusada despues de un flush fallido tira AssertionFailure y convierte este
			// 409 legitimo en un 500.
			log.info("Alta de espacio rechazada por nombre repetido: organizationId={} consultorioId={}",
					organizationId, consultorioId);
			throw new EspacioNameTakenException(nombre);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("tipo", tipo.name());
		detalles.put("capacidad", String.valueOf(persistido.getCapacidad()));
		detalles.put("validFrom", String.valueOf(persistido.getValidFrom()));
		auditar(AuditEvents.ESPACIO_CREATED, persistido, accountId,
				null, "ACTIVO", null, detalles, ahora);

		log.info("Espacio creado: organizationId={} consultorioId={} espacioId={}",
				organizationId, consultorioId, persistido.getId());

		return EspacioView.de(persistido, ahora);
	}

	// =================================================================================
	// Edicion (RF-M04-002, RF-M04-007) — y el caso borde de la capacidad
	// =================================================================================

	/**
	 * Edita nombre, tipo, capacidad, notas y vigencia de un espacio vigente.
	 *
	 * <h2>El protocolo, y por que el orden no es negociable</h2>
	 *
	 * <pre>
	 *   1. evaluar consultorio:manage sobre la sede
	 *   2. SELECT * FROM espacio WHERE ... FOR UPDATE   &lt;- PRIMERA lectura de la fila
	 *   3. comparar la version enviada
	 *   4. si la capacidad BAJA: preguntar el pico de ocupacion a las sondas
	 *   5. si el pico supera la capacidad pedida -&gt; 409 espacio-capacity-below-occupancy
	 *   6. mutar + auditar, en la misma transaccion
	 * </pre>
	 *
	 * <p><b>2. El {@code FOR UPDATE} es la PRIMERA lectura de la fila y eso importa.</b> Una
	 * lectura no bloqueante antes fijaria el snapshot de la transaccion, y el conteo del paso 4
	 * devolveria datos anteriores al commit del competidor <b>aunque el lock ya estuviera
	 * tomado</b>: el lock serializa el ACCESO, no la VISIBILIDAD. Es el mismo bug que
	 * {@code LimiteDePlanConcurrenteIT} encontro sobre el limite de plan y el mismo que
	 * {@code countActiveForShare} cierra del lado de las sedes.
	 *
	 * <p><b>{@code READ_COMMITTED} es cinturon sobre tirantes</b>, por el mismo motivo: sin el,
	 * cualquier lectura que alguien agregue mas adelante antes del lock reintroduce la carrera
	 * en silencio.
	 *
	 * <p><b>4. Solo se pregunta cuando la capacidad BAJA.</b> Subirla no puede violar nada, y
	 * consultar a las sondas en cada edicion de un nombre pagaria el costo de la agenda de F5
	 * para nada.
	 *
	 * <h2>El caso borde "capacidad reducida bajo ocupacion"</h2>
	 *
	 * <p>Desde el paquete E-1 {@link EspacioOccupancyProbe} tiene implementacion
	 * ({@code scheduling.infrastructure.EspacioOcupadoPorTurnos}): si el pico de turnos
	 * pendientes supera la capacidad pedida, la edicion responde
	 * {@code 409 espacio-capacity-below-occupancy}. {@code activity} (M28) todavia no declara
	 * sonda, asi que las inscripciones no cuentan. La otra carrera, la <b>actualizacion
	 * perdida</b> —dos reducciones simultaneas donde la segunda pisa a la primera—, la sostienen
	 * el lock y la comparacion de version, y {@code EspaciosConcurrenteIT} la corre con hilos
	 * reales afirmando contra la base.
	 *
	 * @throws EspacioInactiveException si el espacio esta dado de baja (409)
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409)
	 * @throws EspacioCapacityBelowOccupancyException si la capacidad pedida no alcanza (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EspacioView update(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long espacioId,
			EspacioEdicionCommand command) {

		// 1.
		PermissionDecision decision = exigirGestion(actor, organizationId, consultorioId);

		// 2. Primera lectura de la fila, y con lock. Ver el javadoc: el orden no es cosmetico.
		Espacio espacio = espacioRepository
				.findByIdForUpdate(espacioId, organizationId, consultorioId)
				.orElseThrow(() -> new EspacioNotAccessibleException(espacioId));

		if (!espacio.isOperable()) {
			throw new EspacioInactiveException(espacioId, EspacioInactiveException.Operacion.EDICION);
		}

		// 3.
		if (espacio.getVersion() != command.expectedVersion()) {
			throw new OptimisticLockingFailureException(
					"El espacio fue modificado por otra operacion");
		}

		Instant ahora = Instant.now();
		String nombre = command.name() == null ? null : exigirNombre(command.name());
		Integer capacidad = exigirCapacidadValida(command.capacidad());

		// 4 y 5.
		if (capacidad != null && capacidad < espacio.getCapacidad()) {
			exigirCapacidadSuficiente(organizationId, espacioId, capacidad, ahora);
		}

		Map<String, String> detalles = cambios(espacio, nombre, command.tipo(), capacidad, command);

		espacio.updateDatos(
				nombre, command.tipo(), capacidad, command.notes(),
				command.validFrom(), command.validUntil(), command.clearValidUntil());

		Espacio guardado;
		try {
			guardado = espacioRepository.saveAndFlush(espacio);
		} catch (DataIntegrityViolationException nombreRepetido) {
			log.info("Edicion de espacio rechazada por nombre repetido: espacioId={}", espacioId);
			throw new EspacioNameTakenException(nombre);
		}

		// 6. Se audita aunque no haya cambiado nada: el intento de edicion tambien es un hecho, y
		//    omitirlo dejaria un hueco en el historial justo cuando alguien lo revisa.
		auditar(AuditEvents.ESPACIO_UPDATED, guardado, actor.accountId(),
				null, null, null, detalles, ahora);
		auditarSoporte(decision, organizationId, consultorioId, actor, espacioId, ahora);

		return EspacioView.de(guardado, ahora);
	}

	// =================================================================================
	// Baja logica (RF-M04-006)
	// =================================================================================

	/**
	 * Da de baja un espacio. Motivo obligatorio, sin borrado fisico.
	 *
	 * <p>El espacio deja de ofrecerse para reservas nuevas (RN-M04-002) y <b>nada de lo que
	 * ocurrio en el se modifica</b> (RN-M04-003): sigue siendo legible por id, conserva su
	 * nombre y su estado, y libera ese nombre para un espacio nuevo. <b>No hay reactivacion</b>:
	 * ningun RF de M04 la pide, y un recurso que vuelve de una refaccion es una ventana
	 * operativa nueva, no una baja deshecha.
	 *
	 * <p>El lock {@code FOR UPDATE} y el {@code READ_COMMITTED} estan por el mismo motivo que en
	 * {@link #update}: la consulta a las sondas decide, y una decision que se toma sobre un
	 * snapshot viejo es una decision equivocada que nadie reproduce con un solo usuario.
	 *
	 * <p><b>El caso borde "baja con reservas futuras"</b>: desde el paquete E-1 la sonda de
	 * turnos de {@code scheduling} declara los turnos pendientes del espacio y la baja responde
	 * {@code 409 espacio-has-active-references}. La baja no los cancela: que hacer con ellos
	 * sigue siendo decision abierta, del mismo tipo que la que 02.01 dejo para las sedes, y
	 * ADR-0011 prohibe una cancelacion en cascada sin confirmacion explicita, motivo y auditoria
	 * por turno.
	 *
	 * @throws EspacioInactiveException si ya estaba dado de baja (409)
	 * @throws EspacioHasActiveReferencesException si alguna sonda declara ocupacion (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EspacioView deactivate(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long espacioId,
			String motivo) {

		PermissionDecision decision = exigirGestion(actor, organizationId, consultorioId);
		exigirMotivo(motivo);

		Espacio espacio = espacioRepository
				.findByIdForUpdate(espacioId, organizationId, consultorioId)
				.orElseThrow(() -> new EspacioNotAccessibleException(espacioId));

		if (!espacio.isOperable()) {
			throw new EspacioInactiveException(espacioId, EspacioInactiveException.Operacion.BAJA);
		}

		Instant ahora = Instant.now();
		exigirSinOcupacionVigente(organizationId, espacioId, ahora);

		espacio.deactivate(ahora, motivo);
		Espacio guardado = espacioRepository.save(espacio);

		auditar(AuditEvents.ESPACIO_DEACTIVATED, guardado, actor.accountId(),
				"ACTIVO", "INACTIVO", motivo, Map.of(), ahora);
		auditarSoporte(decision, organizationId, consultorioId, actor, espacioId, ahora);

		log.info("Espacio dado de baja: organizationId={} consultorioId={} espacioId={}",
				organizationId, consultorioId, espacioId);

		return EspacioView.de(guardado, ahora);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * Exige {@code consultorio:manage} SOBRE ESA SEDE, en una MUTACION.
	 *
	 * <p>Con la sede como alcance, un {@code CONSULTORIO_ADMIN} pasa sobre la suya y no sobre
	 * las demas, y un {@code ORG_ADMIN} pasa sobre todas — que es exactamente lo que dice la
	 * matriz seccion 6, sin ningun caso especial escrito: es la formula del evaluador operando.
	 *
	 * <p><b>La sede que se pasa al evaluador es la de la RUTA, no la del contexto</b>, y antes
	 * se comprueba que sean la misma. El motivo es el mismo que sostiene
	 * {@code AuthorizationGuard.requireSameContext}: un actor puede administrar la sede A y
	 * estar operando con un token acotado a la B; sin la comparacion, un id de A en la URL le
	 * daria acceso administrativo mientras trabaja en B.
	 *
	 * <p>El uso de soporte se registra <b>dentro</b> de la transaccion del negocio: hay una
	 * mutacion que no puede quedar confirmada sin rastro. Lo hace el llamador, con el id del
	 * espacio, que aca todavia no se conoce.
	 */
	private PermissionDecision exigirGestion(
			OperatingActor actor, long organizationId, long consultorioId) {

		exigirContextoDeLaSede(actor, organizationId, consultorioId);
		return permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	/**
	 * Exige poder LEER los espacios de esa sede.
	 *
	 * <p>Exige <b>pertenencia y despues {@code espacio:read}</b> con la sede como alcance. En ese
	 * orden: el tenant ajeno sale por 404 antes de que el evaluador pueda contestar 403. Ver la
	 * tabla del javadoc de la clase.
	 *
	 * <p><b>Rechaza con 404 y no con 403.</b> Responder "prohibido" confirmaria que esa
	 * organizacion o esa sede existen, y bastaria recorrer ids para mapear el SaaS. La unica
	 * excepcion es la falta de contexto, que es 403: el actor todavia no eligio donde trabaja y
	 * el frontend tiene que poder traducirlo a "elegi un consultorio". Nunca 401 — el
	 * interceptor del frontend borra el token ante cualquier 401 y dejaria al usuario en un
	 * bucle de login del que no sale.
	 *
	 * <p><b>El {@code PLATFORM_ADMIN} va por otro camino y es mas estricto, no menos.</b> No
	 * tiene membership en ningun tenant (matriz seccion 1.3), asi que la comprobacion de
	 * pertenencia lo dejaria afuera siempre. Pasa por el mismo {@code espacio:read}, que en su
	 * columna tiene alcance {@code SOPORTE} —criterio de la enmienda 9.7, aplicado a la fila
	 * aprobada el 25/08/2026—: sin {@code support_access} vigente es 403 exista o no
	 * la organizacion —respuesta uniforme, no sirve para enumerar— y con soporte queda
	 * {@code SUPPORT_ACCESS_USED} en la auditoria del tenant leido.
	 */
	private void exigirLectura(OperatingActor actor, long organizationId, long consultorioId) {
		if (actor.platformAdmin()) {
			exigirLecturaDePlataforma(actor, organizationId, consultorioId);
			return;
		}

		if (actor.contextOrganizationId() == null) {
			log.info("Request sin contexto validado sobre espacios: accountId={} organizationId={}",
					actor.accountId(), organizationId);
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| !accountContextDirectory.hasActiveMembership(actor.accountId(), organizationId)) {
			log.info("Lectura de espacios de organizacion ajena rechazada: accountId={} organizationId={}",
					actor.accountId(), organizationId);
			throw new ConsultorioNotAccessibleException(consultorioId);
		}

		// La pertenencia se comprobo ARRIBA y a proposito, antes del permiso: un tenant ajeno
		// tiene que salir por 404, y un 403 del evaluador confirmaria que esa organizacion
		// existe. Adentro del propio tenant, quien decide es el permiso.
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.ESPACIO_READ,
				organizationId,
				consultorioId,
				null,
				Instant.now()));

		// La sede tiene que existir y ser del tenant. Se lee activa o no: un espacio de una sede
		// dada de baja sigue siendo consultable, igual que la sede misma (RF-M03-004).
		exigirSedeDelTenant(organizationId, consultorioId);
	}

	private void exigirLecturaDePlataforma(
			OperatingActor actor, long organizationId, long consultorioId) {

		Instant ahora = Instant.now();
		PermissionDecision decision = permissionGuard.requirePermission(PermissionQuery.of(
				actor.accountId(), PermissionCodes.ESPACIO_READ, organizationId, ahora));

		if (decision.viaSupportAccess()) {
			// Transaccion propia: esta lectura es readOnly y ademas puede terminar en 404, y en
			// los dos casos la fila se perderia. Ver SupportAccessAuditor.
			supportAccessAuditor.record(usoDeSoporte(
					organizationId, consultorioId, actor.accountId(),
					PermissionCodes.ESPACIO_READ, consultorioId, ahora));
		}
		exigirSedeDelTenant(organizationId, consultorioId);
	}

	/**
	 * Exige que la organizacion Y la sede pedidas sean las del contexto ya validado.
	 *
	 * <p>Son dos comprobaciones y hacen falta las dos: la de organizacion impide operar sobre
	 * un tenant ajeno, y la de sede impide que un {@code ORG_ADMIN} con contexto en la sede A
	 * mute la B por URL sin haber cambiado de contexto. La segunda es mas estricta de lo que la
	 * matriz exige —{@code ORG_ADMIN} tiene alcance organizacion— y es deliberado: la sede del
	 * contexto es la unica que el sistema revalido contra la base en este request, y
	 * {@code OperatingActor.consultorioId} sale de ahi y nunca del cliente.
	 *
	 * <p>Un {@code PLATFORM_ADMIN} no tiene contexto de tenant y no muta espacios: la
	 * administracion del catalogo fisico de un centro es del centro. Cae en el 403 de abajo, y
	 * eso es lo correcto — no hay ninguna operacion de rescate que exija crear un box ajeno.
	 */
	private void exigirContextoDeLaSede(
			OperatingActor actor, long organizationId, long consultorioId) {

		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Mutacion de espacios sin contexto validado: accountId={} organizationId={}",
					actor.accountId(), organizationId);
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| actor.consultorioId() != consultorioId) {
			log.info("Mutacion de espacios fuera del contexto del request: "
					+ "accountId={} organizationId={} consultorioId={}",
					actor.accountId(), organizationId, consultorioId);
			throw new ConsultorioNotAccessibleException(consultorioId);
		}
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	/**
	 * Carga el espacio acotado al tenant Y a la sede, activo o no.
	 *
	 * <p>Nunca por id pelado: un id de otro tenant no resuelve y responde 404, igual que uno
	 * inexistente. Distinguirlos permitiria contar los boxes de otros centros.
	 */
	private Espacio cargar(long organizationId, long consultorioId, long espacioId) {
		return espacioRepository
				.findByIdAndOrganizationIdAndConsultorioId(espacioId, organizationId, consultorioId)
				.orElseThrow(() -> new EspacioNotAccessibleException(espacioId));
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNotAccessibleException(consultorioId));
	}

	/**
	 * La sede tiene que estar ACTIVA para recibir espacios nuevos.
	 *
	 * <p>409 y no 404: la sede existe y el actor la puede leer; lo que no admite la operacion es
	 * su estado. Es RN-M03-003 aplicada un nivel mas abajo — una sede inactiva no origina hechos
	 * nuevos, y un box nuevo es un hecho nuevo.
	 */
	private ConsultorioSnapshot exigirSedeOperable(long organizationId, long consultorioId) {
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		if (!sede.active()) {
			throw new ConsultorioNotOperableException(consultorioId);
		}
		return sede;
	}

	/**
	 * Pregunta a las sondas si la capacidad pedida alcanza (caso borde de la etapa).
	 *
	 * <p>Se toma el MAXIMO de los picos y no la suma: cada sonda responde por su propio
	 * vocabulario —turnos, inscripciones— y sumarlos contaria dos veces a la misma persona que
	 * ocupa un lugar por dos motivos distintos.
	 */
	private void exigirCapacidadSuficiente(
			long organizationId, long espacioId, int capacidadPedida, Instant at) {

		for (EspacioOccupancyProbe sonda : occupancyProbes) {
			EspacioOccupancyProbe.Occupancy ocupacion =
					sonda.peakOccupancyFrom(organizationId, espacioId, at);
			if (ocupacion != null && ocupacion.peak() > capacidadPedida) {
				log.info("Reduccion de capacidad rechazada por ocupacion: espacioId={} pedida={} pico={}",
						espacioId, capacidadPedida, ocupacion.peak());
				throw new EspacioCapacityBelowOccupancyException(
						espacioId, capacidadPedida, ocupacion.peak(), ocupacion.type());
			}
		}
	}

	private void exigirSinOcupacionVigente(long organizationId, long espacioId, Instant at) {
		for (EspacioOccupancyProbe sonda : occupancyProbes) {
			EspacioOccupancyProbe.Occupancy ocupacion =
					sonda.peakOccupancyFrom(organizationId, espacioId, at);
			if (ocupacion != null && ocupacion.hayAlguna()) {
				throw new EspacioHasActiveReferencesException(
						espacioId, ocupacion.type(), ocupacion.peak());
			}
		}
	}

	private DisponibilidadView disponibilidadDe(
			Espacio espacio, long organizationId, Instant desde, Instant hasta) {

		long comprometidos = 0L;
		for (EspacioOccupancyProbe sonda : occupancyProbes) {
			EspacioOccupancyProbe.Occupancy ocupacion =
					sonda.peakOccupancyFrom(organizationId, espacio.getId(), desde);
			if (ocupacion != null) {
				comprometidos = Math.max(comprometidos, ocupacion.peak());
			}
		}
		long libres = Math.max(0L, espacio.getCapacidad() - comprometidos);

		return new DisponibilidadView(
				espacio.getId(),
				espacio.getName(),
				espacio.getTipo().name(),
				espacio.getCapacidad(),
				desde,
				hasta,
				espacio.estaEnServicioDurante(desde, hasta) && libres > 0,
				comprometidos,
				libres);
	}

	private Map<String, String> cambios(
			Espacio espacio,
			String nombre,
			EspacioTipo tipo,
			Integer capacidad,
			EspacioEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(espacio.getName())) {
			// El nombre anterior si va a la auditoria: es un dato del propio tenant y sin el la
			// fila no responde que cambio. Lo que nunca va son secretos ni contenido clinico.
			detalles.put("name", espacio.getName() + " -> " + nombre);
		}
		if (tipo != null && tipo != espacio.getTipo()) {
			detalles.put("tipo", espacio.getTipo() + " -> " + tipo);
		}
		if (capacidad != null && capacidad != espacio.getCapacidad()) {
			detalles.put("capacidad", espacio.getCapacidad() + " -> " + capacidad);
		}
		if (command.validFrom() != null && !command.validFrom().equals(espacio.getValidFrom())) {
			detalles.put("validFrom", espacio.getValidFrom() + " -> " + command.validFrom());
		}
		if (command.clearValidUntil()) {
			detalles.put("validUntil", espacio.getValidUntil() + " -> (sin fin)");
		} else if (command.validUntil() != null
				&& !command.validUntil().equals(espacio.getValidUntil())) {
			detalles.put("validUntil", espacio.getValidUntil() + " -> " + command.validUntil());
		}
		return detalles;
	}

	/**
	 * Registra {@code SUPPORT_ACCESS_USED} cuando la concesion se apoyo en un acceso de soporte.
	 *
	 * <p>La matriz seccion 7 pide auditar CADA operacion amparada por soporte, no solo su
	 * otorgamiento: lo que hay que poder reconstruir es que hizo el administrador de plataforma
	 * mientras estuvo adentro del tenant.
	 */
	private void auditarSoporte(
			PermissionDecision decision,
			long organizationId,
			Long consultorioId,
			OperatingActor actor,
			Long espacioId,
			Instant ahora) {

		if (!decision.viaSupportAccess()) {
			return;
		}
		auditTrail.record(usoDeSoporte(
				organizationId, consultorioId, actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE, espacioId, ahora));
	}

	private static AuditEntry usoDeSoporte(
			Long organizationId,
			Long consultorioId,
			long actorAccountId,
			String permissionCode,
			Long entityId,
			Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("permissionCode", permissionCode);
		return new AuditEntry(
				organizationId, consultorioId, actorAccountId,
				"SUPPORT_ACCESS_USED", "SupportAccess", entityId,
				null, null, detalles, null, AuditEvents.correlationId(), ahora);
	}

	private void auditar(
			String eventType,
			Espacio espacio,
			long actorAccountId,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				espacio.getOrganizationId(),
				espacio.getConsultorioId(),
				actorAccountId,
				eventType,
				AuditEvents.ENTITY_ESPACIO,
				espacio.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	private static String exigirNombre(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("El nombre del espacio es obligatorio");
		}
		return name.strip();
	}

	private static void exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de un espacio: sin el, la "
							+ "auditoria no responde por que seis meses despues");
		}
	}

	/**
	 * Acota la capacidad a un rango con sentido operativo.
	 *
	 * <p>El piso lo garantiza ademas {@code ck_espacio_capacidad_positiva} en la base. El techo
	 * <b>no sale de ningun RF</b> y es deliberadamente amplio: lo unico que impide son los
	 * valores que no pueden ser una intencion, como un gimnasio para diez mil personas. Vive en
	 * la aplicacion y no en el esquema para poder cambiarlo sin migracion.
	 */
	private static Integer exigirCapacidadValida(Integer capacidad) {
		if (capacidad == null) {
			return null;
		}
		if (capacidad < 1 || capacidad > 1000) {
			throw new IllegalArgumentException(
					"La capacidad de un espacio debe estar entre 1 y 1000 personas");
		}
		return capacidad;
	}

	/**
	 * Acota la ventana de la consulta de disponibilidad.
	 *
	 * <p>El tope de 31 dias no sale de ningun RF y es el mismo criterio que 01.03 aplico a la
	 * consulta de auditoria: sin tope, una ventana de diez años sobre un centro grande es un
	 * scan y un problema de disponibilidad. Se elige 31 porque la unidad natural de una agenda
	 * es el mes.
	 */
	private static void exigirVentana(Instant desde, Instant hasta) {
		if (desde == null || hasta == null) {
			throw new IllegalArgumentException(
					"La consulta de disponibilidad exige una ventana con inicio y fin");
		}
		if (!hasta.isAfter(desde)) {
			throw new IllegalArgumentException(
					"El fin de la ventana debe ser posterior a su inicio");
		}
		if (java.time.Duration.between(desde, hasta).toDays() > 31) {
			throw new IllegalArgumentException(
					"La ventana de disponibilidad no puede superar los 31 dias");
		}
	}
}
