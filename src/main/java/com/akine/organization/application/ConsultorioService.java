package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.ConsultorioAlta;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.exception.ConsultorioHasActiveReferencesException;
import com.akine.organization.domain.exception.ConsultorioInactiveException;
import com.akine.organization.domain.exception.ConsultorioNameTakenException;
import com.akine.organization.domain.exception.ConsultorioNotAccessibleException;
import com.akine.organization.domain.exception.LastConsultorioException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.ConsultorioAltaRepositoryPort;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.ConsultorioDeactivationProbe;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.PlanGate;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Administracion de sedes: alta adicional, lectura, edicion y baja logica (M03).
 *
 * <h2>Que expande esta etapa y que NO reescribe</h2>
 *
 * <p>El onboarding compuesto ya crea Cuenta + Organizacion + primer Consultorio + Membership
 * propietaria en una transaccion (ADR-0008) y <b>no se toca</b>. El selector de contexto ya
 * funciona de punta a punta desde 01.02, asi que RF-M03-005 <b>no genera endpoint nuevo</b>: lo
 * cumple {@code POST /api/v1/auth/context}. Lo que 02.01 agrega es el camino paralelo de las
 * sedes 2..N.
 *
 * <h2>El alta: por que este metodo NO lleva @Transactional</h2>
 *
 * <p>Porque la transaccion la abre {@link PlanGate#createWithinLimit}, y tiene que abrirla el.
 * El gate se niega a unirse a una transaccion existente y lanza {@code IllegalStateException}
 * si hay una activa: al unirse, Spring <b>ignora la isolation declarada</b> —la fija quien abre
 * la transaccion— y el alta correria en {@code REPEATABLE READ}, donde el conteo posterior al
 * bloqueo lee de un snapshot anterior al commit del competidor y el limite del plan se viola en
 * silencio.
 *
 * <p><b>Quien "arregle" esto agregandole {@code @Transactional} a {@link #create} rompe el
 * limite de plan sin que ningun test unitario se entere.</b> El que si se entera es
 * {@code LimiteDePlanConcurrenteIT}, con hilos reales contra MySQL real.
 *
 * <h2>El orden de bloqueo del sistema: subscription -&gt; organization</h2>
 *
 * <p>Es unico y no es una preferencia (D-11, cerrada por el usuario el 23/08/2026). El alta de
 * sede lo toma sin decidirlo: el gate bloquea {@code subscription} con {@code FOR UPDATE} y
 * despues el {@code INSERT INTO consultorio} toma un lock compartido sobre la fila de
 * {@code organization} por {@code fk_consultorio_organization}. La baja lo toma
 * <b>explicitamente</b>, aunque no consuma ni libere cupo de plan: un orden que se respeta solo
 * cuando hay limites de por medio no es un orden.
 *
 * <p>El otro lado de esta regla vive en {@code MembershipService.bloquearTenant}, y los dos
 * comentarios se citan mutuamente a proposito: <b>no existe ninguna herramienta que compare
 * estos dos lugares</b>. La unica defensa contra que alguien invierta uno de los dos es ese par
 * de comentarios y un test de concurrencia; un orden de bloqueo perdido se manifiesta como un
 * {@code CannotAcquireLockException} esporadico que nadie reproduce con un solo usuario.
 *
 * <h2>Auditoria</h2>
 *
 * <p>Se escribe DENTRO de la transaccion del negocio, nunca en un listener post-commit: uno que
 * falla deja la mutacion sin rastro. Y hay que recordar el corolario incomodo: una excepcion de
 * negocio hace rollback de todo lo escrito antes de lanzarla, <b>incluida la auditoria</b>. Por
 * eso los rechazos que igual deben quedar registrados —limite de plan, permiso denegado— tienen
 * auditores con transaccion propia y no se escriben desde aca.
 */
@Service
public class ConsultorioService {

	private static final Logger log = LoggerFactory.getLogger(ConsultorioService.class);

	/** Valor imposible: {@code consultorio.id} es AUTO_INCREMENT y arranca en 1. */
	private static final long SIN_EXCLUSION = -1L;

	private final ConsultorioRepositoryPort consultorioRepository;
	private final ConsultorioAltaRepositoryPort altaRepository;
	private final OrganizationRepositoryPort organizationRepository;
	private final SubscriptionRepositoryPort subscriptionRepository;
	private final PlanGate planGate;
	private final TenantUsageCounter usageCounter;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final SupportAccessReadAuditor supportAccessReadAuditor;
	private final List<ConsultorioDeactivationProbe> deactivationProbes;

	public ConsultorioService(
			ConsultorioRepositoryPort consultorioRepository,
			ConsultorioAltaRepositoryPort altaRepository,
			OrganizationRepositoryPort organizationRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			PlanGate planGate,
			TenantUsageCounter usageCounter,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SupportAccessReadAuditor supportAccessReadAuditor,
			List<ConsultorioDeactivationProbe> deactivationProbes) {
		this.consultorioRepository = consultorioRepository;
		this.altaRepository = altaRepository;
		this.organizationRepository = organizationRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.planGate = planGate;
		this.usageCounter = usageCounter;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.supportAccessReadAuditor = supportAccessReadAuditor;
		this.deactivationProbes = deactivationProbes;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Una sede por id, <b>activa o no</b>.
	 *
	 * <p>Devolver tambien las inactivas es RF-M03-004: "los historicos previos permanecen
	 * disponibles". Un 404 sobre una sede dada de baja estaria borrando historia por la puerta
	 * de atras.
	 *
	 * @throws ConsultorioNotAccessibleException si no existe o es de otro tenant (404)
	 */
	// RECUPERA el readOnly = true que 02.01 le habia sacado. El motivo por el que se lo saco
	// —que SUPPORT_ACCESS_USED se pierde en el flush MANUAL de una transaccion de solo lectura—
	// era correcto y la solucion no: sin readOnly el caso feliz se arreglaba y el que importa
	// quedaba abierto igual. `cargar` lanza ConsultorioNotAccessibleException DESPUES de evaluar
	// el permiso, asi que en el unico escenario que el acceso de soporte existe para hacer
	// trazable —un id ajeno o inexistente recorrido por un administrador de plataforma— la
	// transaccion hace rollback y se lleva puesta la fila que se acababa de escribir. El
	// comentario que estaba aca describia correctamente solo la mitad del problema.
	//
	// La auditoria de esta lectura la escribe ahora SupportAccessReadAuditor en su propia
	// transaccion (REQUIRES_NEW): sobrevive al readOnly y sobrevive al 404.
	@Transactional(readOnly = true)
	public ConsultorioView find(OperatingActor actor, long organizationId, long consultorioId) {
		exigirSobreLaSedeEnLectura(actor, organizationId, consultorioId);
		return ConsultorioView.de(cargar(organizationId, consultorioId));
	}

	// =================================================================================
	// Alta (RF-M03-001)
	// =================================================================================

	/**
	 * Da de alta una sede adicional, dentro del limite del plan.
	 *
	 * <p><b>SIN {@code @Transactional}: la abre el gate.</b> Ver el javadoc de la clase; no es
	 * un olvido.
	 *
	 * <p>El permiso se evalua <b>dentro</b> del supplier, o sea dentro de la transaccion y
	 * despues del bloqueo. Evaluarlo antes dejaria una ventana en la que una revocacion
	 * concurrente se cuela entre la autorizacion y el commit.
	 *
	 * <p>Se exige alcance ORGANIZACION ({@code consultorioId = null} en la consulta) y eso
	 * excluye al {@code CONSULTORIO_ADMIN} sin ningun caso especial escrito: es la formula del
	 * evaluador operando. Un alta no tiene consultorio objetivo —la sede todavia no existe—, asi
	 * que la interseccion con un alcance de una sola sede es vacia.
	 *
	 * @throws com.akine.organization.domain.exception.PlanLimitExceededException si se alcanzo
	 *         {@code MAX_CONSULTORIOS} (409)
	 * @throws com.akine.organization.domain.exception.SubscriptionSuspendedException si la
	 *         suscripcion no habilita mutaciones (409)
	 * @throws ConsultorioNameTakenException si ya hay una sede vigente con ese nombre (409)
	 * @throws IdempotencyKeyConflictException si la clave se reuso con otro contenido (409)
	 */
	public ConsultorioView create(
			OperatingActor actor, long organizationId, ConsultorioAltaCommand command) {

		exigirClave(command.idempotencyKey());

		// Camino feliz del reintento por timeout de red: la clave ya se uso, se devuelve lo
		// mismo de antes. Va ANTES del gate a proposito: un replay no consume cupo de plan ni
		// necesita bloquear la suscripcion, y hacerlo pasar por el gate haria que un reintento
		// pudiera ser rechazado por un limite que el alta original ya respeto.
		Optional<ConsultorioView> replay = replayDe(organizationId, command);
		if (replay.isPresent()) {
			return replay.get();
		}

		return planGate.createWithinLimit(
				organizationId,
				LimitCode.MAX_CONSULTORIOS,
				() -> usageCounter.count(LimitCode.MAX_CONSULTORIOS, organizationId),
				() -> persistir(actor, organizationId, command));
	}

	/**
	 * Todo lo que el alta escribe, dentro de la transaccion que abrio el gate.
	 *
	 * <p>Un fallo en cualquier paso revierte todo, incluido el consumo del cupo: no hay estado
	 * intermedio observable (ADR-0008 aplicado al camino nuevo).
	 */
	private ConsultorioView persistir(
			OperatingActor actor, long organizationId, ConsultorioAltaCommand command) {

		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCode.CONSULTORIO_MANAGE.code(),
				organizationId,
				null,
				null,
				Instant.now()));

		Organization organization = requireActiveOrganization(organizationId);
		String nombre = exigirNombre(command.name());
		// La zona propuesta por defecto es la de la organizacion. NO es un fallback permanente:
		// es el valor inicial, se persiste en la fila y a partir de ahi la sede tiene zona
		// propia. Un fallback en LECTURA haria que editar la zona de la organizacion moviera en
		// silencio el dia operativo historico de las sedes que nunca fijaron la suya (R-5).
		String zona = ZonasHorarias.exigirValida(
				command.timezone() == null || command.timezone().isBlank()
						? organization.getTimezone()
						: command.timezone());

		Consultorio consultorio = new Consultorio(
				organizationId, nombre, zona, exigirSlotValido(command.slotMinutes()));
		consultorio.updateDatos(
				null, null, null,
				command.legalName(), command.taxId(), command.addressLine(),
				command.phone(), command.contactEmail());

		Consultorio persistido;
		try {
			// saveAndFlush: la clave duplicada tiene que aparecer ACA y no al commit, donde el
			// catch ya no la ve y el advice generico devuelve 500.
			persistido = consultorioRepository.saveAndFlush(consultorio);
		} catch (DataIntegrityViolationException claveDuplicada) {
			// Y desde aca NO se vuelve a tocar la sesion JPA: ni auditoria, ni lecturas. Una
			// sesion reusada despues de un flush fallido tira AssertionFailure y convierte este
			// 409 legitimo en un 500.
			log.info("Alta de sede rechazada por nombre repetido: organizationId={}", organizationId);
			throw new ConsultorioNameTakenException(nombre);
		}

		try {
			altaRepository.saveAndFlush(new ConsultorioAlta(
					organizationId, command.idempotencyKey(), command.requestHash(),
					persistido.getId(), Instant.now()));
		} catch (DataIntegrityViolationException claveEnCurso) {
			log.info("Alta de sede concurrente con la misma clave de idempotencia: organizationId={}",
					organizationId);
			throw new ConsultorioAltaEnCursoException(command.idempotencyKey());
		}

		Instant ahora = Instant.now();
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("timezone", zona);
		detalles.put("slotMinutes", String.valueOf(persistido.getSlotMinutes()));
		auditar(AuditEvents.CONSULTORIO_CREATED, persistido, actor.accountId(),
				null, ConsultorioView.de(persistido).estado(), null, detalles, ahora);
		auditarSoporte(decision, organizationId, persistido.getId(), actor, ahora);

		log.info("Sede creada: organizationId={} consultorioId={}",
				organizationId, persistido.getId());

		return ConsultorioView.de(persistido);
	}

	// =================================================================================
	// Edicion (RF-M03-003)
	// =================================================================================

	/**
	 * Edita los datos, la zona y el intervalo de una sede vigente.
	 *
	 * <p>Se evalua el permiso <b>con la sede como alcance</b>, no con {@code null}: asi un
	 * {@code CONSULTORIO_ADMIN} puede editar la suya, que es literalmente lo que le da la matriz
	 * §6. La diferencia con la baja es deliberada y esta explicada en {@link #deactivate}.
	 *
	 * @throws ConsultorioInactiveException si la sede esta dada de baja (409)
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409)
	 */
	@Transactional
	public ConsultorioView update(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			ConsultorioEdicionCommand command) {

		PermissionDecision decision = exigirSobreLaSede(actor, organizationId, consultorioId);
		Consultorio consultorio = cargar(organizationId, consultorioId);

		if (!consultorio.isOperable()) {
			throw new ConsultorioInactiveException(
					consultorioId, ConsultorioInactiveException.Operacion.EDICION);
		}
		if (consultorio.getVersion() != command.expectedVersion()) {
			throw new OptimisticLockingFailureException(
					"La sede fue modificada por otra operacion");
		}

		String nombre = command.name() == null ? null : exigirNombre(command.name());
		String zona = command.timezone() == null || command.timezone().isBlank()
				? null
				: ZonasHorarias.exigirValida(command.timezone());
		Integer slot = exigirSlotValido(command.slotMinutes());

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(consultorio.getName())) {
			// El nombre anterior si va a la auditoria: es un dato del propio tenant y sin el la
			// fila no responde que cambio. Lo que nunca va son secretos ni contenido clinico.
			detalles.put("name", consultorio.getName() + " -> " + nombre);
		}
		if (zona != null && !zona.equals(consultorio.getTimezone())) {
			detalles.put("timezone", consultorio.getTimezone() + " -> " + zona);
		}
		if (slot != null && slot != consultorio.getSlotMinutes()) {
			detalles.put("slotMinutes", consultorio.getSlotMinutes() + " -> " + slot);
		}

		consultorio.updateDatos(
				nombre, zona, slot,
				command.legalName(), command.taxId(), command.addressLine(),
				command.phone(), command.contactEmail());

		Consultorio guardado;
		try {
			guardado = consultorioRepository.saveAndFlush(consultorio);
		} catch (DataIntegrityViolationException nombreRepetido) {
			log.info("Edicion de sede rechazada por nombre repetido: consultorioId={}", consultorioId);
			throw new ConsultorioNameTakenException(nombre);
		}

		Instant ahora = Instant.now();
		// Se audita aunque no haya cambiado nada: el intento de edicion tambien es un hecho, y
		// omitirlo dejaria un hueco en el historial justo cuando alguien lo revisa.
		auditar(AuditEvents.CONSULTORIO_UPDATED, guardado, actor.accountId(),
				null, null, null, detalles, ahora);
		auditarSoporte(decision, organizationId, consultorioId, actor, ahora);

		return ConsultorioView.de(guardado);
	}

	// =================================================================================
	// Baja logica (RF-M03-004)
	// =================================================================================

	/**
	 * Da de baja una sede. Motivo obligatorio, sin borrado fisico.
	 *
	 * <h2>El protocolo, y por que el orden no es negociable</h2>
	 *
	 * <pre>
	 *   1. SELECT id FROM subscription WHERE organization_id = ? FOR UPDATE  &lt;- PRIMERA sentencia
	 *   2. evaluar el permiso del actor
	 *   3. cargar la sede, acotada al tenant
	 *   4. contar las OTRAS sedes activas con FOR SHARE
	 *   5. si el conteo es 0 -&gt; 409 last-consultorio-required
	 *   6. preguntar a las sondas de referencias vigentes
	 *   7. deactivate + auditoria, en la misma transaccion
	 * </pre>
	 *
	 * <p><b>1. La baja bloquea la suscripcion aunque no toque el plan.</b> Es contraintuitivo y
	 * es deliberado: el orden de bloqueo del sistema es unico —{@code subscription} y despues
	 * {@code organization}— y un orden que se respeta solo cuando hay limites de por medio no es
	 * un orden. El costo es que dos bajas de sedes distintas del mismo tenant se serializan; es
	 * una operacion administrativa poco frecuente.
	 *
	 * <p><b>1-bis. Es la PRIMERA sentencia.</b> Un {@code findById} antes fijaria el snapshot de
	 * la transaccion y el conteo del paso 4 leeria datos anteriores al commit del competidor
	 * aunque el lock ya estuviera tomado: el lock serializa el acceso, no la visibilidad.
	 *
	 * <p><b>4. El conteo lleva {@code FOR SHARE} y {@code READ_COMMITTED} es cinturon sobre
	 * tirantes.</b> Sin el lock compartido, dos bajas simultaneas de las dos ultimas sedes
	 * cuentan cada una a la otra, las dos pasan, y el tenant queda sin ninguna sede activa:
	 * {@code GET /me/contexts} devuelve vacio para todos y solo se recupera con SQL manual.
	 *
	 * <p><b>Este invariante no lo puede hacer valer el {@code PlanGate}:</b> el gate cuenta hacia
	 * arriba contra {@code MAX_CONSULTORIOS} y esto cuenta hacia abajo, contra un piso de 1 que
	 * ningun plan declara.
	 *
	 * <h2>Solo ORG_ADMIN — decision del implementador, enmienda pendiente de confirmacion</h2>
	 *
	 * <p>El permiso se evalua con alcance ORGANIZACION ({@code consultorioId = null}), lo que
	 * <b>excluye al {@code CONSULTORIO_ADMIN} sobre su propia sede</b>. La matriz §6 literalmente
	 * se lo permite: le da {@code consultorio:manage} con alcance consultorio, y la baja de la
	 * propia sede cae dentro de ese alcance.
	 *
	 * <p>Se aparta de la lectura literal por analogia con el {@code self-revoke} que 01.03 ya
	 * prohibe: <b>nadie destruye el alcance desde el que opera</b>. Un {@code CONSULTORIO_ADMIN}
	 * que da de baja su unica sede se deja a si mismo sin contexto de trabajo y sin forma de
	 * deshacerlo; solo un {@code ORG_ADMIN} puede rescatarlo.
	 *
	 * <p><b>Queda anotado como enmienda pendiente de confirmacion del usuario</b>, igual que se
	 * hizo con la enmienda §9.1 de la matriz. Si se confirma la lectura literal, el cambio es
	 * pasar {@code consultorioId} en la consulta de abajo, y el invariante de la ultima sede
	 * sigue protegiendo el caso peor.
	 *
	 * @throws LastConsultorioException si es la ultima sede activa del tenant (409)
	 * @throws ConsultorioInactiveException si ya estaba dada de baja (409)
	 * @throws ConsultorioHasActiveReferencesException si alguna sonda declara referencias (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ConsultorioView deactivate(
			OperatingActor actor, long organizationId, long consultorioId, String motivo) {

		// 1. Orden de bloqueo unico del sistema: subscription -> organization. Primera
		//    sentencia, igual que en MembershipService.bloquearTenant. Los dos comentarios se
		//    citan mutuamente porque ninguna herramienta compara estos dos lugares.
		bloquearTenant(organizationId);

		exigirMotivo(motivo);

		// 2. Alcance ORGANIZACION a proposito: ver el javadoc de arriba.
		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCode.CONSULTORIO_MANAGE.code(),
				organizationId,
				null,
				null,
				Instant.now()));

		// 3.
		Consultorio consultorio = cargar(organizationId, consultorioId);
		if (!consultorio.isOperable()) {
			throw new ConsultorioInactiveException(
					consultorioId, ConsultorioInactiveException.Operacion.BAJA);
		}

		// 4 y 5. El conteo que DECIDE es una lectura con lock, jamas una lectura consistente.
		long otrasActivas = consultorioRepository.countActiveForShare(organizationId, consultorioId);
		if (otrasActivas == 0) {
			log.info("Baja rechazada por invariante de ultima sede: organizationId={}", organizationId);
			throw new LastConsultorioException(organizationId);
		}

		// 6. En F1 la lista esta vacia y esto no hace nada. Existe para que agregar el bloqueo
		//    por turnos futuros en F5 sea sumar una clase, no rediseñar la baja.
		Instant ahora = Instant.now();
		exigirSinReferenciasVigentes(organizationId, consultorioId, ahora);

		// 7.
		String estadoAnterior = ConsultorioView.de(consultorio).estado();
		consultorio.deactivate(ahora, motivo);
		Consultorio guardado = consultorioRepository.save(consultorio);

		auditar(AuditEvents.CONSULTORIO_DEACTIVATED, guardado, actor.accountId(),
				estadoAnterior, ConsultorioView.de(guardado).estado(), motivo, Map.of(), ahora);
		auditarSoporte(decision, organizationId, consultorioId, actor, ahora);

		log.info("Sede dada de baja: organizationId={} consultorioId={}", organizationId, consultorioId);

		return ConsultorioView.de(guardado);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	/**
	 * Toma el punto de serializacion del tenant. <b>Primera sentencia de la baja.</b>
	 *
	 * <p>La fila es la de {@code subscription} y no la de {@code organization}: es el orden de
	 * bloqueo unico del sistema, el mismo que toma {@code PlanGate.createWithinLimit} y el mismo
	 * que documenta {@code MembershipService}. Invertirlo aca reintroduce un deadlock entre
	 * etapas que no se reproduce con un solo usuario.
	 */
	private void bloquearTenant(long organizationId) {
		subscriptionRepository.findByOrganizationIdForUpdate(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	/**
	 * Exige {@code consultorio:manage} SOBRE ESA SEDE, en una MUTACION.
	 *
	 * <p>Con la sede como alcance, un {@code CONSULTORIO_ADMIN} pasa sobre la suya y no sobre
	 * las demas — que es exactamente lo que dice la matriz §6.
	 *
	 * <p>El uso de soporte se registra <b>dentro</b> de la transaccion del negocio: es la regla
	 * T-2, y aca si aplica, porque hay una mutacion que no puede quedar confirmada sin rastro.
	 * <b>Lo registra el llamador</b>, despues del evento del negocio, igual que en el alta y la
	 * baja. Registrarlo tambien aca dejaba DOS {@code SUPPORT_ACCESS_USED} por cada edicion
	 * amparada en soporte, y quien reconstruye la intervencion contaba dos operaciones.
	 */
	private PermissionDecision exigirSobreLaSede(
			OperatingActor actor, long organizationId, long consultorioId) {

		return permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCode.CONSULTORIO_MANAGE.code(),
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	/**
	 * Lo mismo, en una LECTURA, con una diferencia que no es cosmetica.
	 *
	 * <p>La entrada {@code SUPPORT_ACCESS_USED} se escribe por {@link SupportAccessReadAuditor},
	 * que abre su propia transaccion. Escribirla en la de la lectura la pierde dos veces: por el
	 * {@code readOnly} —flush MANUAL, la fila no llega a la base— y por el rollback, porque
	 * {@code cargar} lanza el 404 despues de esta evaluacion. Es el mismo par
	 * {@code exigir}/{@code exigirEnLectura} que ya tenia {@code MembershipService}.
	 */
	private void exigirSobreLaSedeEnLectura(
			OperatingActor actor, long organizationId, long consultorioId) {

		Instant ahora = Instant.now();
		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCode.CONSULTORIO_MANAGE.code(),
				organizationId,
				consultorioId,
				null,
				ahora));

		if (decision.viaSupportAccess()) {
			supportAccessReadAuditor.record(AuditEvents.usoDeSoporte(
					organizationId, consultorioId, actor.accountId(),
					PermissionCode.CONSULTORIO_MANAGE.code(), consultorioId, ahora));
		}
	}

	/**
	 * Carga la sede acotada al tenant, activa o no.
	 *
	 * <p>Se busca por {@code (id, organizationId)} y nunca por id pelado: un id de otro tenant no
	 * resuelve y responde 404, igual que uno inexistente. Distinguirlos permitiria enumerar las
	 * sedes de otros centros.
	 */
	private Consultorio cargar(long organizationId, long consultorioId) {
		return consultorioRepository.findByIdAndOrganizationId(consultorioId, organizationId)
				.orElseThrow(() -> new ConsultorioNotAccessibleException(consultorioId));
	}

	private Organization requireActiveOrganization(long organizationId) {
		return organizationRepository.findByIdAndActiveTrue(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	private Optional<ConsultorioView> replayDe(
			long organizationId, ConsultorioAltaCommand command) {

		return altaRepository
				.findByOrganizationIdAndIdempotencyKey(organizationId, command.idempotencyKey())
				.map(registro -> {
					if (!registro.matchesRequestHash(command.requestHash())) {
						// Misma clave, otro contenido: es un error del cliente y no un
						// reintento. Devolverle el resultado viejo lo dejaria creyendo que se
						// creo lo que pidio ahora.
						throw new IdempotencyKeyConflictException(command.idempotencyKey());
					}
					return ConsultorioView.de(
							cargar(organizationId, registro.getConsultorioId()));
				});
	}

	private void exigirSinReferenciasVigentes(
			long organizationId, long consultorioId, Instant at) {

		for (ConsultorioDeactivationProbe sonda : deactivationProbes) {
			ConsultorioDeactivationProbe.ActiveReferences referencias =
					sonda.activeReferencesOn(organizationId, consultorioId, at);
			if (referencias != null && referencias.bloquean()) {
				throw new ConsultorioHasActiveReferencesException(
						consultorioId, referencias.type(), referencias.count());
			}
		}
	}

	/**
	 * Registra {@code SUPPORT_ACCESS_USED} cuando la concesion se apoyo en un acceso de soporte.
	 *
	 * <p>La matriz §7 pide auditar CADA operacion amparada por soporte, no solo su otorgamiento:
	 * lo que hay que poder reconstruir es que hizo el administrador de plataforma mientras
	 * estuvo adentro del tenant.
	 */
	private void auditarSoporte(
			PermissionDecision decision,
			long organizationId,
			Long consultorioId,
			OperatingActor actor,
			Instant ahora) {

		if (!decision.viaSupportAccess()) {
			return;
		}
		auditTrail.record(AuditEvents.usoDeSoporte(
				organizationId, consultorioId, actor.accountId(),
				PermissionCode.CONSULTORIO_MANAGE.code(), consultorioId, ahora));
	}

	private void auditar(
			String eventType,
			Consultorio consultorio,
			long actorAccountId,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				consultorio.getOrganizationId(),
				consultorio.getId(),
				actorAccountId,
				eventType,
				AuditEvents.ENTITY_CONSULTORIO,
				consultorio.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	private static String exigirNombre(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("El nombre de la sede es obligatorio");
		}
		return name.strip();
	}

	private static void exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de una sede: sin el, la auditoria "
							+ "no responde por que seis meses despues");
		}
	}

	private static void exigirClave(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException(
					"El alta de una sede exige una clave de idempotencia: es lo que hace que un "
							+ "reintento por timeout de red no cree una segunda sede");
		}
	}

	/**
	 * Acota el intervalo de agenda a un rango con sentido operativo.
	 *
	 * <p>No sale de ningun RF —es parte de la decision revisable sobre el intervalo— y es
	 * deliberadamente amplio: lo unico que impide son los valores que no pueden ser una
	 * intencion, como cero, negativos o un dia entero.
	 */
	private static Integer exigirSlotValido(Integer slotMinutes) {
		if (slotMinutes == null) {
			return null;
		}
		if (slotMinutes < 5 || slotMinutes > 480) {
			throw new IllegalArgumentException(
					"El intervalo de agenda debe estar entre 5 y 480 minutos");
		}
		return slotMinutes;
	}
}
