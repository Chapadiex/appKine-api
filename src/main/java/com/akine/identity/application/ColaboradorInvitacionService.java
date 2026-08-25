package com.akine.identity.application;

import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.EstadoInvitacion;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.exception.ColaboradorYaVinculadoException;
import com.akine.identity.domain.exception.InvitacionNotAccessibleException;
import com.akine.identity.domain.exception.InvitacionPendienteDuplicadaException;
import com.akine.identity.domain.exception.InvitacionVencidaException;
import com.akine.identity.domain.port.ColaboradorInvitacionRepositoryPort;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.InvitationMembershipCommand;
import com.akine.organization.spi.MembershipProvisioning;
import com.akine.organization.spi.OrganizationDirectory;
import com.akine.organization.spi.OrganizationSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ciclo de vida de la invitacion a colaborar (RF-M05-001, RF-M05-002, AKINE-02.03).
 *
 * <h2>Que agrega esta etapa y que NO reemplaza</h2>
 *
 * <p>{@link DirectMembershipService} sigue existiendo y no se toca: el alta directa es un click
 * para quien <b>ya tiene cuenta</b> en AKINE, no exige aceptacion y responde 404 si el email no
 * esta registrado. La invitacion es el camino para quien <b>todavia no</b>, y ademas le da a la
 * persona la decision de entrar. Los dos conviven a proposito, y por eso 02.03 es aditiva: no
 * hay ninguna operacion del contrato que cambie de forma.
 *
 * <h2>El token es la autoridad, y por eso hay dos mitades</h2>
 *
 * <p>Las operaciones de este servicio se parten en dos grupos que <b>no comparten forma de
 * autorizar</b>:
 *
 * <ul>
 *   <li><b>Del lado del administrador</b> —emitir, listar, reenviar, cancelar— se exige
 *       {@code colaborador:manage} sobre el alcance pedido, igual que el alta directa.</li>
 *   <li><b>Del lado del invitado</b> —consultar, aceptar, rechazar— no se exige nada, porque no
 *       hay nada que exigirle: no pertenece al tenant y puede no tener ni cuenta. Lo que
 *       autoriza es el token, que prueba que llega al buzon al que se emitio la invitacion.</li>
 * </ul>
 *
 * <p>De ahi que las tres operaciones publicas sean {@code POST} y reciban el token <b>en el
 * cuerpo</b>: un token en la query string queda en los logs del servidor, en el historial del
 * navegador y en el {@code Referer} de cualquier recurso externo que la pantalla cargue.
 *
 * <h2>Lo que se responde cuando el token no resuelve</h2>
 *
 * <p>404 uniforme (ADR-0018): token inventado, invitacion de otro tenant y token ya consumido
 * responden igual. La <b>unica</b> excepcion es el token vencido, que responde
 * {@code 409 invitacion-vencida}: quien lo presenta ya demostro que es el destinatario, asi que
 * no hay nada que enumerar, y decirle "vencio" en vez de "no existe" es la diferencia entre
 * pedir un reenvio y reportar que el sistema esta roto.
 *
 * <h2>Emitir no consume cupo de plan; aceptar si</h2>
 *
 * <p>El limite {@code MAX_MIEMBROS_ACTIVOS} lo aplica {@code organization} al crear la
 * membership, que es cuando el miembro existe. Una invitacion pendiente no ocupa lugar: si
 * ocupara, un administrador podria dejar sin cupo a su propia organizacion invitando a diez
 * personas que nunca respondan. La contracara declarada es que <b>cinco invitaciones pendientes
 * con un solo lugar libre significan que cuatro van a recibir un 409 al aceptar</b>, y eso es
 * preferible: el que llega primero entra, y el tope del plan se mide sobre gente que trabaja.
 */
@Service
public class ColaboradorInvitacionService {

	private static final Logger log = LoggerFactory.getLogger(ColaboradorInvitacionService.class);

	/**
	 * Permiso de la matriz que habilita administrar colaboradores.
	 *
	 * <p>Viaja como texto y no como el enum de {@code organization.domain}: ese paquete es
	 * privado de su modulo y ArchUnit rechaza importarlo. Misma decision que
	 * {@link DirectMembershipService}.
	 */
	private static final String PERMISO_GESTION_DE_COLABORADORES = "colaborador:manage";

	private final ColaboradorInvitacionRepositoryPort invitacionRepository;
	private final CuentaRepositoryPort cuentaRepository;
	private final MembershipProvisioning membershipProvisioning;
	private final AccountContextDirectory contextDirectory;
	private final OrganizationDirectory organizationDirectory;
	private final ConsultorioDirectory consultorioDirectory;
	private final PermissionGuard permissionGuard;
	private final TokenGenerator tokenGenerator;
	private final VerificationLinkBuilder linkBuilder;
	private final NotificationOutboxPort notificationOutbox;
	private final PasswordHasher passwordHasher;
	private final PasswordPolicy passwordPolicy;
	private final AuditTrail auditTrail;
	private final IdentityClock clock;

	@SuppressWarnings("java:S107")
	public ColaboradorInvitacionService(
			ColaboradorInvitacionRepositoryPort invitacionRepository,
			CuentaRepositoryPort cuentaRepository,
			MembershipProvisioning membershipProvisioning,
			AccountContextDirectory contextDirectory,
			OrganizationDirectory organizationDirectory,
			ConsultorioDirectory consultorioDirectory,
			PermissionGuard permissionGuard,
			TokenGenerator tokenGenerator,
			VerificationLinkBuilder linkBuilder,
			NotificationOutboxPort notificationOutbox,
			PasswordHasher passwordHasher,
			PasswordPolicy passwordPolicy,
			AuditTrail auditTrail,
			IdentityClock clock) {

		this.invitacionRepository = invitacionRepository;
		this.cuentaRepository = cuentaRepository;
		this.membershipProvisioning = membershipProvisioning;
		this.contextDirectory = contextDirectory;
		this.organizationDirectory = organizationDirectory;
		this.consultorioDirectory = consultorioDirectory;
		this.permissionGuard = permissionGuard;
		this.tokenGenerator = tokenGenerator;
		this.linkBuilder = linkBuilder;
		this.notificationOutbox = notificationOutbox;
		this.passwordHasher = passwordHasher;
		this.passwordPolicy = passwordPolicy;
		this.auditTrail = auditTrail;
		this.clock = clock;
	}

	// =================================================================================
	// Lado del administrador
	// =================================================================================

	/**
	 * Emite una invitacion (RF-M05-001).
	 *
	 * <p>El orden de los pasos es el mismo que el del alta directa y por el mismo motivo:
	 * <b>permiso antes que email</b>. Al reves, cualquier cuenta con contexto podria preguntar
	 * por direcciones ajenas y leer la respuesta antes de que el permiso la frenara.
	 *
	 * <p><b>Invitar a alguien que ya trabaja ahi es 409 y no un correo.</b> Se comprueba contra
	 * la membership vigente, no contra la existencia de la cuenta: la primera es informacion que
	 * el administrador ya puede listar en su propia organizacion, la segunda no.
	 *
	 * @throws AccessDeniedException si el actor opera sin contexto de organizacion (403)
	 * @throws ColaboradorYaVinculadoException si esa cuenta ya es miembro (409)
	 * @throws InvitacionPendienteDuplicadaException si ya hay una pendiente igual (409)
	 */
	@Transactional
	public InvitacionView invitar(DirectMembershipService.Actor actor, InvitacionAltaCommand command) {
		long organizationId = exigirContexto(actor);
		Instant ahora = clock.now();

		// PASO 1 - Permiso, antes que nada. La sede que se evalua es la del VINCULO propuesto:
		// quien administra la sede A no puede invitar a la sede B.
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PERMISO_GESTION_DE_COLABORADORES,
				organizationId,
				command.consultorioId(),
				null,
				ahora));

		// PASO 2 - Recien ahora el email. Antes del paso 1 seria un oraculo abierto.
		String emailNormalizado = EmailNormalizado.of(command.email());
		exigirQueNoSeaYaMiembro(emailNormalizado, organizationId);

		// PASO 3 - Una sola invitacion viva por persona y alcance. Se comprueba aca para dar el
		// 409 con su codigo propio, y la base lo sostiene igual con su unique: entre esta
		// lectura y el insert puede colarse otro administrador.
		if (pendienteDe(organizationId, command.consultorioId(), emailNormalizado).isPresent()) {
			throw new InvitacionPendienteDuplicadaException(emailNormalizado);
		}

		String tokenPlano = tokenGenerator.nuevoToken();
		ColaboradorInvitacion invitacion = new ColaboradorInvitacion(
				organizationId,
				command.consultorioId(),
				emailNormalizado,
				command.roleCode(),
				TokenDigest.of(tokenPlano),
				ahora.plus(TipoTokenVerificacion.INVITACION.vigencia()),
				actor.accountId());

		ColaboradorInvitacion persistida = guardarNueva(invitacion, emailNormalizado);

		encolarCorreo(persistida, tokenPlano, actor.accountId());

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("email", emailNormalizado);
		detalles.put("roleCode", command.roleCode());
		IdentityAuditEvents.registrarInvitacion(
				auditTrail, IdentityAuditEvents.INVITACION_EMITIDA, organizationId,
				command.consultorioId(), persistida.getId(), actor.accountId(),
				null, EstadoInvitacion.PENDIENTE.name(), detalles, null, ahora);

		log.info("Invitacion emitida: invitacionId={} organizationId={} consultorioId={}",
				persistida.getId(), organizationId, command.consultorioId());

		return InvitacionView.de(persistida, ahora);
	}

	/**
	 * Invitaciones del tenant, opcionalmente filtradas por estado.
	 *
	 * <p>Sin paginar: una organizacion tiene decenas de invitaciones en toda su vida, no miles,
	 * y el filtro por estado es lo que hace util al listado. El dia que un tenant grande lo
	 * desmienta, paginar es aditivo.
	 *
	 * @throws AccessDeniedException si el actor opera sin contexto de organizacion (403)
	 */
	@Transactional(readOnly = true)
	public List<InvitacionView> listar(DirectMembershipService.Actor actor, EstadoInvitacion estado) {
		long organizationId = exigirContexto(actor);
		Instant ahora = clock.now();

		// La lectura del listado pide el mismo permiso que la escritura, y es a proposito: lo
		// que se ve aca son direcciones de correo de personas que todavia no aceptaron nada.
		// No es la lista de colaboradores —esa tiene su propio `colaborador:read`—, es la lista
		// de a quien se le escribio.
		permissionGuard.requirePermission(PermissionQuery.of(
				actor.accountId(), PERMISO_GESTION_DE_COLABORADORES, organizationId, ahora));

		List<ColaboradorInvitacion> invitaciones = estado == null
				? invitacionRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
				: invitacionRepository.findByOrganizationIdAndEstadoOrderByCreatedAtDesc(
						organizationId, estado);

		return invitaciones.stream().map(invitacion -> InvitacionView.de(invitacion, ahora)).toList();
	}

	/**
	 * Rota el token de una invitacion pendiente y vuelve a mandar el correo (RF-M26-001).
	 *
	 * <p>Es lo que hay que hacer cuando el enlace vencio o cuando el invitado dice que no le
	 * llego. <b>No crea una invitacion nueva</b>: conserva el id, la autoria y la fecha original,
	 * que es lo que hace que el listado siga diciendo desde cuando se esta esperando respuesta.
	 * Y el token anterior deja de servir en el mismo acto.
	 */
	@Transactional
	public InvitacionView reenviar(DirectMembershipService.Actor actor, long invitacionId) {
		long organizationId = exigirContexto(actor);
		Instant ahora = clock.now();

		ColaboradorInvitacion invitacion = cargarDelTenant(invitacionId, organizationId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PERMISO_GESTION_DE_COLABORADORES, organizationId,
				invitacion.getConsultorioId(), null, ahora));

		String tokenPlano = tokenGenerator.nuevoToken();
		invitacion.reenviar(
				TokenDigest.of(tokenPlano),
				ahora.plus(TipoTokenVerificacion.INVITACION.vigencia()));

		ColaboradorInvitacion persistida = invitacionRepository.save(invitacion);
		encolarCorreo(persistida, tokenPlano, actor.accountId());

		IdentityAuditEvents.registrarInvitacion(
				auditTrail, IdentityAuditEvents.INVITACION_REENVIADA, organizationId,
				invitacion.getConsultorioId(), invitacionId, actor.accountId(),
				null, null, Map.of(), null, ahora);

		return InvitacionView.de(persistida, ahora);
	}

	/**
	 * Retira una invitacion antes de que la respondan.
	 *
	 * <p>El motivo es obligatorio —lo exige la entidad y lo exige la base— porque cancelar es
	 * una decision del administrador y tiene que responder por que seis meses despues. Rechazar,
	 * que es la decision del otro lado, no lo exige.
	 */
	@Transactional
	public InvitacionView cancelar(
			DirectMembershipService.Actor actor, long invitacionId, String motivo) {

		long organizationId = exigirContexto(actor);
		Instant ahora = clock.now();

		ColaboradorInvitacion invitacion = cargarDelTenant(invitacionId, organizationId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PERMISO_GESTION_DE_COLABORADORES, organizationId,
				invitacion.getConsultorioId(), null, ahora));

		invitacion.cancelar(motivo, ahora);
		ColaboradorInvitacion persistida = invitacionRepository.save(invitacion);

		IdentityAuditEvents.registrarInvitacion(
				auditTrail, IdentityAuditEvents.INVITACION_CANCELADA, organizationId,
				invitacion.getConsultorioId(), invitacionId, actor.accountId(),
				EstadoInvitacion.PENDIENTE.name(), EstadoInvitacion.CANCELADA.name(),
				Map.of(), motivo, ahora);

		return InvitacionView.de(persistida, ahora);
	}

	// =================================================================================
	// Lado del invitado — sin sesion, autorizado por el token
	// =================================================================================

	/**
	 * Que dice la invitacion, para que el invitado decida (RF-M05-002).
	 *
	 * <p><b>No consume el token.</b> Es la leccion que dejo el enlace de un solo uso de 01.02:
	 * un enlace que se gasta al mirarlo deja al usuario sin poder aceptar en cuanto la pantalla
	 * se recargue, o en cuanto el cliente de correo lo pre-visite para generar una vista previa.
	 *
	 * <p>Lo que devuelve es {@link InvitacionPreview} y no la vista del administrador: quien
	 * presenta el token no pertenece al tenant, y cada id que se le entregue es una pieza mas
	 * para adivinar el resto.
	 *
	 * @throws InvitacionNotAccessibleException si el token no resuelve o ya se resolvio (404)
	 * @throws InvitacionVencidaException si el enlace expiro (409)
	 */
	@Transactional(readOnly = true)
	public InvitacionPreview consultar(String tokenPlano) {
		Instant ahora = clock.now();
		ColaboradorInvitacion invitacion = porToken(tokenPlano, ahora);

		OrganizationSnapshot organizacion = organizationDirectory
				.find(invitacion.getOrganizationId())
				.orElseThrow(InvitacionNotAccessibleException::new);

		String consultorioNombre = invitacion.getConsultorioId() == null
				? null
				: consultorioDirectory
						.find(invitacion.getOrganizationId(), invitacion.getConsultorioId())
						.map(ConsultorioSnapshot::name)
						.orElse(null);

		boolean requiereRegistro = cuentaRepository
				.findByEmailNormalizado(invitacion.getEmailNormalizado())
				.isEmpty();

		return new InvitacionPreview(
				organizacion.name(),
				consultorioNombre,
				invitacion.getRoleCode(),
				invitacion.getEmailNormalizado(),
				invitacion.getExpiraEn(),
				requiereRegistro);
	}

	/**
	 * Acepta la invitacion: crea la cuenta si hace falta y el vinculo siempre (RF-M05-002).
	 *
	 * <h2>Por que la cuenta nace ACTIVA y sin correo de activacion</h2>
	 *
	 * <p>El registro self-service crea la cuenta {@code PENDIENTE_ACTIVACION} y manda un correo,
	 * porque ahi nadie probo todavia que la direccion sea suya: cualquiera puede tipear el email
	 * de otro. Aca eso <b>ya esta probado</b> — el token llego a ese buzon y volvio—, asi que
	 * exigir una segunda vuelta de correo verificaria por segunda vez lo mismo y agregaria el
	 * unico paso donde la mitad de la gente abandona.
	 *
	 * <p>La contracara es que el token de invitacion tiene el mismo poder que un token de
	 * activacion, y por eso vive hasheado, se consume al usarse y expira.
	 *
	 * <h2>Una sola transaccion, en READ COMMITTED</h2>
	 *
	 * <p>Cuenta, membership e invitacion se mueven juntas o no se mueve ninguna: una cuenta
	 * creada con la invitacion todavia pendiente dejaria a la persona con credenciales que no
	 * abren nada y un enlace que al reintentar dice "ya existe una cuenta". La isolation es
	 * explicita porque {@code createFromInvitation} consulta el limite de plan bajo bloqueo, y
	 * ese conteo en REPEATABLE READ lee de un snapshot anterior al commit del competidor y deja
	 * pasar un miembro de mas, en silencio.
	 *
	 * @throws InvitacionNotAccessibleException si el token no resuelve o ya se resolvio (404)
	 * @throws InvitacionVencidaException si el enlace expiro (409)
	 * @throws ColaboradorYaVinculadoException si entre medio ya la vincularon (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoAceptacion aceptar(InvitacionAceptacionCommand command) {
		Instant ahora = clock.now();
		ColaboradorInvitacion invitacion = porToken(command.token(), ahora);

		Optional<Cuenta> existente = cuentaRepository
				.findByEmailNormalizado(invitacion.getEmailNormalizado());

		boolean cuentaCreada = existente.isEmpty();
		Cuenta cuenta = existente.isPresent()
				? existente.get()
				: crearCuentaDelInvitado(invitacion, command, ahora);

		long membershipId;
		try {
			membershipId = membershipProvisioning.createFromInvitation(
					invitacion.getOrganizationId(),
					new InvitationMembershipCommand(
							cuenta.getId(),
							invitacion.getConsultorioId(),
							invitacion.getRoleCode(),
							invitacion.getId(),
							invitacion.getInvitadaPorAccountId()));
		} catch (RuntimeException vinculoRechazado) {
			// El caso concreto que se traduce es "ya tiene vinculo": entre la invitacion y la
			// respuesta alguien la dio de alta por el camino directo. Se distingue por el
			// nombre de la clase y no importando la excepcion, que ArchUnit prohibe.
			if ("MembershipAlreadyExistsException"
					.equals(vinculoRechazado.getClass().getSimpleName())) {
				throw new ColaboradorYaVinculadoException();
			}
			throw vinculoRechazado;
		}

		invitacion.aceptar(cuenta.getId(), membershipId, ahora);
		invitacionRepository.save(invitacion);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("membershipId", String.valueOf(membershipId));
		detalles.put("cuentaCreada", String.valueOf(cuentaCreada));
		IdentityAuditEvents.registrarInvitacion(
				auditTrail, IdentityAuditEvents.INVITACION_ACEPTADA,
				invitacion.getOrganizationId(), invitacion.getConsultorioId(), invitacion.getId(),
				cuenta.getId(), EstadoInvitacion.PENDIENTE.name(),
				EstadoInvitacion.ACEPTADA.name(), detalles, null, ahora);

		log.info("Invitacion aceptada: invitacionId={} membershipId={} cuentaCreada={}",
				invitacion.getId(), membershipId, cuentaCreada);

		return new ResultadoAceptacion(
				cuenta.getId(),
				membershipId,
				invitacion.getOrganizationId(),
				invitacion.getConsultorioId(),
				cuentaCreada);
	}

	/**
	 * El invitado dice que no (RF-M05-002).
	 *
	 * <p>Consume la invitacion —queda RECHAZADA y su enlace deja de servir— y <b>no crea ninguna
	 * cuenta</b>: quien no quiere entrar no tiene por que quedar registrado en AKINE.
	 *
	 * <p>El motivo es opcional. Rechazar una oferta de trabajo no exige explicarse.
	 */
	@Transactional
	public void rechazar(String tokenPlano, String motivo) {
		Instant ahora = clock.now();
		ColaboradorInvitacion invitacion = porToken(tokenPlano, ahora);

		invitacion.rechazar(motivo == null || motivo.isBlank() ? null : motivo.strip(), ahora);
		invitacionRepository.save(invitacion);

		// El actor es null y no la cuenta del invitado: puede no tener ninguna. Lo que
		// identifica al hecho es la invitacion, que es la entidad del evento.
		IdentityAuditEvents.registrarInvitacion(
				auditTrail, IdentityAuditEvents.INVITACION_RECHAZADA,
				invitacion.getOrganizationId(), invitacion.getConsultorioId(), invitacion.getId(),
				null, EstadoInvitacion.PENDIENTE.name(), EstadoInvitacion.RECHAZADA.name(),
				Map.of(), motivo, ahora);
	}

	// =================================================================================
	// Interna
	// =================================================================================

	/**
	 * Resuelve el token a una invitacion utilizable, o falla.
	 *
	 * <p>Los tres motivos de 404 —token inventado, invitacion de otro tenant, invitacion ya
	 * resuelta— responden igual (ADR-0018). El vencimiento es el unico que se distingue, y el
	 * javadoc de {@link InvitacionVencidaException} explica por que eso no abre ningun oraculo.
	 */
	private ColaboradorInvitacion porToken(String tokenPlano, Instant ahora) {
		if (tokenPlano == null || tokenPlano.isBlank()) {
			throw new InvitacionNotAccessibleException();
		}

		ColaboradorInvitacion invitacion = invitacionRepository
				.findByTokenHash(TokenDigest.of(tokenPlano))
				.orElseThrow(InvitacionNotAccessibleException::new);

		if (invitacion.getEstado().esTerminal()) {
			throw new InvitacionNotAccessibleException();
		}
		if (invitacion.estaVencida(ahora)) {
			throw new InvitacionVencidaException();
		}
		return invitacion;
	}

	/**
	 * Crea la cuenta del invitado, ya activa.
	 *
	 * <p>La contrasena pasa por la misma politica que el registro self-service: que el camino de
	 * entrada sea otro no vuelve aceptable una contrasena que el sistema rechaza en la puerta
	 * principal.
	 */
	private Cuenta crearCuentaDelInvitado(
			ColaboradorInvitacion invitacion, InvitacionAceptacionCommand command, Instant ahora) {

		if (command.nombre() == null || command.nombre().isBlank()) {
			throw new IllegalArgumentException(
					"El nombre es obligatorio para crear la cuenta del invitado");
		}
		passwordPolicy.validar(command.password());

		Cuenta cuenta = new Cuenta(
				invitacion.getEmailNormalizado(),
				command.nombre().strip(),
				command.apellido() == null ? null : command.apellido().strip(),
				passwordHasher.hash(command.password()));

		// Nace PENDIENTE_ACTIVACION —lo fija la maquina de estados— y se activa en el acto: el
		// token de la invitacion ya probo la direccion. Pasar por la transicion en vez de
		// construirla activa mantiene la maquina de estados como unico camino.
		cuenta.transicionarA(EstadoCuenta.ACTIVA, null, ahora);
		Cuenta persistida = cuentaRepository.save(cuenta);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("origen", "invitacion");
		detalles.put("invitacionId", String.valueOf(invitacion.getId()));
		IdentityAuditEvents.registrar(
				auditTrail, IdentityAuditEvents.CUENTA_CREADA, invitacion.getOrganizationId(),
				persistida.getId(), persistida.getId(), null, EstadoCuenta.ACTIVA.name(),
				detalles, null, ahora);

		return persistida;
	}

	/**
	 * Encola el correo con el enlace.
	 *
	 * <p>El token en claro existe en una variable local y en el campo de transporte del outbox;
	 * <b>nunca</b> en el payload consultable (T-11) y nunca en un log, ni a nivel DEBUG.
	 *
	 * <p>La clave idempotente incluye la fecha de expiracion y no solo el id: reenviar emite un
	 * token nuevo sobre la misma invitacion, y con una clave que fuera solo el id el outbox
	 * descartaria el segundo correo por duplicado — el invitado seguiria sin recibir nada, que
	 * es exactamente el problema que el reenvio venia a resolver.
	 */
	private void encolarCorreo(
			ColaboradorInvitacion invitacion, String tokenPlano, long invitadaPorAccountId) {

		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("organizacionNombre", nombreDeLaOrganizacion(invitacion.getOrganizationId()));
		datos.put("invitadoPor", nombreDelInvitante(invitadaPorAccountId));
		notificationOutbox.validarDatosPlantilla(datos);

		notificationOutbox.encolar(new NotificationOutboxPort.Notificacion(
				invitacion.getOrganizationId(),
				NotificationOutboxPort.TipoNotificacion.INVITACION_COLABORADOR,
				invitacion.getEmailNormalizado(),
				datos,
				linkBuilder.enlaceDe(TipoTokenVerificacion.INVITACION, tokenPlano),
				"invitacion:" + invitacion.getId() + ":" + invitacion.getExpiraEn().toEpochMilli()));
	}

	private String nombreDeLaOrganizacion(long organizationId) {
		return organizationDirectory.find(organizationId)
				.map(OrganizationSnapshot::name)
				.orElse("AKINE");
	}

	/**
	 * Como firma el correo quien invita.
	 *
	 * <p>Si la cuenta no resuelve se cae a "Un administrador" en vez de fallar: que el nombre no
	 * este no es motivo para no mandar la invitacion, y la plantilla ya tiene ese default.
	 */
	private String nombreDelInvitante(long accountId) {
		return cuentaRepository.findById(accountId)
				.map(Cuenta::getNombre)
				.filter(nombre -> nombre != null && !nombre.isBlank())
				.orElse("Un administrador");
	}

	/**
	 * Guarda la invitacion nueva traduciendo el choque del unique.
	 *
	 * <p>{@code saveAndFlush} para que la violacion aparezca aca y no al commit, donde el
	 * {@code catch} ya no la ve y el advice generico devuelve 500. Es el mismo protocolo que
	 * {@code MembershipService}, y desde el {@code catch} <b>no se vuelve a tocar la sesion
	 * JPA</b>: una sesion reusada despues de un flush fallido tira {@code AssertionFailure}.
	 */
	private ColaboradorInvitacion guardarNueva(
			ColaboradorInvitacion invitacion, String emailNormalizado) {
		try {
			return invitacionRepository.saveAndFlush(invitacion);
		} catch (DataIntegrityViolationException choque) {
			log.info("Invitacion rechazada por clave duplicada: organizationId={}",
					invitacion.getOrganizationId());
			throw new InvitacionPendienteDuplicadaException(emailNormalizado);
		}
	}

	private Optional<ColaboradorInvitacion> pendienteDe(
			long organizationId, Long consultorioId, String emailNormalizado) {

		// Dos firmas y no una nulable: en JPQL un `= :param` con null no matchea nada, asi que
		// una sola consulta devolveria siempre vacio para el alcance organizacion y dejaria
		// pasar el duplicado que esto existe para detectar.
		return consultorioId == null
				? invitacionRepository
						.findByOrganizationIdAndConsultorioIdIsNullAndEmailNormalizadoAndEstado(
								organizationId, emailNormalizado, EstadoInvitacion.PENDIENTE)
				: invitacionRepository
						.findByOrganizationIdAndConsultorioIdAndEmailNormalizadoAndEstado(
								organizationId, consultorioId, emailNormalizado,
								EstadoInvitacion.PENDIENTE);
	}

	/**
	 * Rechaza invitar a alguien que ya es miembro vigente.
	 *
	 * <p>Se pregunta por la <b>membership</b> y no por la cuenta: quien administra ya puede
	 * listar sus propios colaboradores, asi que esto no le dice nada que no supiera. Preguntar
	 * por la cuenta convertiria el endpoint en un verificador de direcciones, que es lo que
	 * {@code AccountAdminController} documenta al explicar por que no existe la busqueda por
	 * email.
	 */
	private void exigirQueNoSeaYaMiembro(String emailNormalizado, long organizationId) {
		cuentaRepository.findByEmailNormalizado(emailNormalizado)
				.filter(cuenta -> contextDirectory.hasActiveMembership(cuenta.getId(), organizationId))
				.ifPresent(cuenta -> {
					throw new ColaboradorYaVinculadoException();
				});
	}

	private ColaboradorInvitacion cargarDelTenant(long invitacionId, long organizationId) {
		return invitacionRepository.findByIdAndOrganizationId(invitacionId, organizationId)
				.orElseThrow(InvitacionNotAccessibleException::new);
	}

	private static long exigirContexto(DirectMembershipService.Actor actor) {
		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			// Un PLATFORM_ADMIN llega hasta aca sin contexto: TenantContextFilter lo deja pasar
			// a proposito. Invitar necesita un tenant, y elegirlo por el seria inventarlo.
			throw new AccessDeniedException(
					"Invitar a un colaborador requiere un contexto de organizacion activo");
		}
		return organizationId;
	}
}
