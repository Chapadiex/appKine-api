package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.MembershipEstado;
import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.RolePermissions;
import com.akine.organization.domain.exception.FounderRevocationNotAllowedException;
import com.akine.organization.domain.exception.GrantAlreadyActiveException;
import com.akine.organization.domain.exception.LastAdminException;
import com.akine.organization.domain.exception.MembershipAlreadyExistsException;
import com.akine.organization.domain.exception.MembershipNotAccessibleException;
import com.akine.organization.domain.exception.MembershipNotActiveException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.SelfRevokeNotAllowedException;
import com.akine.organization.domain.exception.UnknownPermissionCodeException;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.MembershipGrantRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.organization.spi.InvitationMembershipCommand;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.MembershipProvisioning;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.PlanGate;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Administracion de colaboradores: alta directa, cambio de rol y de alcance, suspension,
 * reactivacion, revocacion y permisos adicionales (RF-M01-002, RF-M02-004, RN-M05-003).
 *
 * <h2>El protocolo de toda mutacion, y por que el orden no es negociable</h2>
 *
 * <pre>
 *   1. SELECT ... FROM subscription WHERE organization_id = ? FOR UPDATE   &lt;- PRIMERA sentencia
 *   2. evaluar el permiso del actor
 *   3. cargar la membership objetivo, acotada al tenant
 *   4. verificar invariantes con LECTURA CON LOCK
 *   5. mutar + auditar, en la misma transaccion
 * </pre>
 *
 * <p><b>1. Por que la fila de {@code subscription} y no la de {@code organization}.</b> Es el
 * orden de bloqueo unico del sistema: {@code subscription} primero, {@code organization}
 * despues. No es una preferencia. El alta de sede entra por {@code PlanGate.createWithinLimit},
 * que toma {@code X} sobre la suscripcion y <b>despues</b> toca {@code organization} por la
 * clave foranea del consultorio. Si las mutaciones de membership tomaran {@code organization}
 * primero, un alta de sede y una revocacion simultaneas en el mismo tenant se bloquearian
 * cruzado, InnoDB mataria a una y el handler generico devolveria un <b>500</b>. Quien
 * "simplifique" esto volviendo a {@code organization} reintroduce un deadlock entre etapas que
 * no se reproduce con un solo usuario.
 *
 * <p>La suscripcion se bloquea <b>aunque la operacion no consuma cupo de plan</b>: revocar un rol
 * no toca ningun limite y bloquea igual. Un orden que se respeta a veces no es un orden.
 *
 * <p><b>1-bis. Por que es la PRIMERA sentencia.</b> Si antes hubiera un {@code findById} de JPA,
 * esa lectura no bloqueante fijaria el snapshot de la transaccion y el conteo del paso 4 leeria
 * datos anteriores al commit del competidor <b>aunque el lock ya estuviera tomado</b>: el lock
 * serializa el acceso, no la visibilidad. Es el bug exacto que aparecio en 01.01 con dos hilos
 * reales contra MySQL real.
 *
 * <p><b>2. Por que el permiso se evalua DESPUES del lock.</b> Asi la ventana entre "tenia
 * permiso" y "commitee" es cero para estas operaciones: la revocacion del propio actor necesita
 * el mismo lock, y por lo tanto no puede colarse en el medio.
 *
 * <p><b>4. Por que la lectura del invariante lleva {@code FOR SHARE}.</b> Ver
 * {@code MembershipRepositoryPort.countActiveOrgAdminsForShare}. Un {@code COUNT(*)} comun ahi
 * reintroduce el bug aunque el paso 1 este bien hecho. Y {@code READ_COMMITTED} declarado en la
 * transaccion es cinturon sobre tirantes: evita que una lectura agregada mas adelante —un
 * {@code findById} que alguien mueva de lugar— vuelva a fijar un snapshot viejo en silencio.
 *
 * <h2>El camino de clave duplicada no vuelve a tocar JPA</h2>
 *
 * <p>Alta directa y alta de grant hacen {@code saveAndFlush}, traducen la
 * {@code DataIntegrityViolationException} a una excepcion de dominio y la dejan propagar. Ni una
 * lectura, ni un {@code save}, ni la auditoria despues del flush fallido: una sesion de JPA
 * reusada tras un flush que fallo tira {@code AssertionFailure} y convierte un 409 legitimo en
 * un 500.
 *
 * <h2>Auditoria</h2>
 *
 * <p>Se escribe DENTRO de la transaccion del negocio, nunca en un listener post-commit: uno que
 * falla deja la mutacion sin rastro. Los rechazos por permiso son la excepcion y tienen su
 * propio auditor con transaccion separada, porque el rollback se los llevaria puestos.
 */
@Service
public class MembershipService implements MembershipProvisioning {

	private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

	/**
	 * Roles que administran algo.
	 *
	 * <p>Lo usa el invariante de self-revoke: "su ultimo rol administrativo en la organizacion".
	 * Sale de la matriz §6 —son los dos roles con {@code colaborador:manage} en su asignacion
	 * base— y no de una lista escrita a mano, para que agregar o quitar esa celda de la matriz
	 * se refleje aca sin que nadie tenga que acordarse.
	 */
	private static final Set<RoleCode> ROLES_ADMINISTRATIVOS = rolesConGestionDeColaboradores();

	private final MembershipRepositoryPort membershipRepository;
	private final MembershipGrantRepositoryPort grantRepository;
	private final SubscriptionRepositoryPort subscriptionRepository;
	private final ConsultorioRepositoryPort consultorioRepository;
	private final PermissionGuard permissionGuard;
	private final PermissionEvaluator permissionEvaluator;
	private final AuditTrail auditTrail;
	private final SupportAccessReadAuditor supportAccessReadAuditor;
	private final AccountIdentityDirectory accountDirectory;
	private final List<ColaboradorDesvinculacionProbe> desvinculacionProbes;
	private final PlanGate planGate;
	private final TenantUsageCounter usageCounter;

	public MembershipService(
			MembershipRepositoryPort membershipRepository,
			MembershipGrantRepositoryPort grantRepository,
			SubscriptionRepositoryPort subscriptionRepository,
			ConsultorioRepositoryPort consultorioRepository,
			PermissionGuard permissionGuard,
			PermissionEvaluator permissionEvaluator,
			AuditTrail auditTrail,
			SupportAccessReadAuditor supportAccessReadAuditor,
			AccountIdentityDirectory accountDirectory,
			List<ColaboradorDesvinculacionProbe> desvinculacionProbes,
			PlanGate planGate,
			TenantUsageCounter usageCounter) {
		this.supportAccessReadAuditor = supportAccessReadAuditor;
		this.accountDirectory = accountDirectory;
		this.desvinculacionProbes = desvinculacionProbes;
		this.planGate = planGate;
		this.usageCounter = usageCounter;
		this.membershipRepository = membershipRepository;
		this.grantRepository = grantRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.consultorioRepository = consultorioRepository;
		this.permissionGuard = permissionGuard;
		this.permissionEvaluator = permissionEvaluator;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Colaboradores del tenant, paginados.
	 *
	 * <p>Devuelve tambien las memberships revocadas: quien administra tiene que poder ver quien
	 * estuvo y por que se fue. La baja logica existe justamente para eso.
	 *
	 * @throws com.akine.organization.domain.exception.PermissionDeniedException si falta
	 *         {@code colaborador:read} (403)
	 * @throws OrganizationNotFoundException si el tenant no esta en el alcance del actor (404)
	 */
	@Transactional(readOnly = true)
	public Page<MembershipView> list(OperatingActor actor, long organizationId, Pageable pageable) {
		exigirEnLectura(actor, organizationId, PermissionCode.COLABORADOR_READ, null);

		Page<Membership> pagina = membershipRepository.findAllByOrganizationId(
				organizationId, pageable);

		// UNA consulta de cuentas por pagina, no una por fila: los ids se juntan primero y se
		// resuelven en lote. Un `identidadesDe(List.of(id))` adentro del map seria el N+1 que
		// convierte un listado de 200 colaboradores en 201 consultas.
		Map<Long, AccountIdentity> cuentas = accountDirectory.identidadesDe(
				pagina.getContent().stream().map(Membership::getAccountId).toList());

		return pagina.map(membership ->
				MembershipView.de(membership, cuentas.get(membership.getAccountId())));
	}

	/**
	 * Una membership concreta del tenant.
	 *
	 * @throws MembershipNotAccessibleException si no existe o es de otro tenant (404)
	 */
	@Transactional(readOnly = true)
	public MembershipView find(OperatingActor actor, long organizationId, long membershipId) {
		exigirEnLectura(actor, organizationId, PermissionCode.COLABORADOR_READ, membershipId);
		return conCuenta(cargar(organizationId, membershipId));
	}

	/**
	 * Permisos adicionales vigentes e historicos de una membership.
	 *
	 * @throws MembershipNotAccessibleException si la membership no es alcanzable (404)
	 */
	@Transactional(readOnly = true)
	public List<MembershipGrantView> grants(
			OperatingActor actor, long organizationId, long membershipId) {
		exigirEnLectura(actor, organizationId, PermissionCode.COLABORADOR_READ, membershipId);
		cargar(organizationId, membershipId);
		return grantRepository.findAllByMembershipIdAndActiveTrue(membershipId).stream()
				.map(MembershipGrantView::de)
				.toList();
	}

	// =================================================================================
	// Alta directa (desviacion declarada de RF-M05-001/002; ver DirectMembershipCommand)
	// =================================================================================

	/** {@inheritDoc} */
	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public long createDirect(
			long actorAccountId,
			boolean actorPlatformAdmin,
			long organizationId,
			DirectMembershipCommand command) {

		// Limite de plan PRIMERO, porque es quien bloquea `subscription`: el orden de bloqueo
		// del sistema es subscription -> organization y este metodo no lo decide, lo respeta.
		// Ver `exigirCupoDeMiembros` para por que este metodo cuenta con un limite que la etapa
		// anterior tenia configurado y no aplicaba.
		exigirCupoDeMiembros(organizationId);

		// Y despues el bloqueo del tenant, que agrega la fila de `organization`.
		bloquearTenant(organizationId);

		// El actor entra sin sede propia: este metodo llega desde `identity.api`, que no le pasa
		// el contexto del request. Lo que se evalua no es donde esta parado el actor sino si
		// puede administrar colaboradores EN LA SEDE A LA QUE QUIERE VINCULAR, y eso va por
		// `exigirEnAlcance` como un dato explicito, no disfrazado de contexto.
		OperatingActor actor = new OperatingActor(actorAccountId, actorPlatformAdmin, null);
		exigirEnAlcance(
				actor, organizationId, PermissionCode.COLABORADOR_MANAGE,
				command.consultorioId(), null);

		exigirMotivo(command.reason(), "el alta de un colaborador");

		return persistirVinculo(
				organizationId,
				command.accountId(),
				command.consultorioId(),
				command.roleCode(),
				actorAccountId,
				command.reason(),
				Map.of("accountId", String.valueOf(command.accountId())));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>La diferencia con {@link #createDirect} es una sola linea: no hay
	 * {@code exigirEnAlcance}.</b> Todo lo demas —el orden de bloqueo, el limite de plan, la
	 * validacion del rol y de la sede, el manejo de la clave duplicada y la auditoria— es
	 * identico, y por eso los dos comparten {@link #persistirVinculo}.
	 *
	 * <p>Por que no hay permiso: quien acepta no pertenece al tenant todavia. Exigirle
	 * {@code colaborador:manage} sobre una organizacion en la que no tiene membership haria que
	 * ninguna invitacion pudiera aceptarse nunca. La autorizacion ya ocurrio —el administrador
	 * emitio la invitacion con ese permiso, y ese acto quedo auditado— y lo que prueba que esta
	 * es la persona invitada es el token, que {@code identity} verifico contra el hash antes de
	 * llamar aca. El javadoc de la interfaz lo dice tambien, porque quien lea el SPI sin abrir
	 * esta clase tiene que enterarse igual.
	 *
	 * <p><b>El rol se revalida.</b> Entre la emision y la aceptacion pueden pasar semanas, y en
	 * el medio {@code RoleCode} puede haber perdido un valor. Confiar en que lo guardado sigue
	 * siendo valido es confiar en el pasado.
	 *
	 * <p>La auditoria deja {@code origen=invitacion} y el id de la invitacion en los detalles.
	 * Sin eso, el evento diria que la membership se creo sola: el actor es el propio invitado,
	 * que no decidio nada — decidio quien lo invito.
	 */
	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public long createFromInvitation(long organizationId, InvitationMembershipCommand command) {
		exigirCupoDeMiembros(organizationId);
		bloquearTenant(organizationId);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("accountId", String.valueOf(command.accountId()));
		detalles.put("origen", "invitacion");
		detalles.put("invitacionId", String.valueOf(command.invitacionId()));
		detalles.put("invitadaPorAccountId", String.valueOf(command.invitadaPorAccountId()));

		return persistirVinculo(
				organizationId,
				command.accountId(),
				command.consultorioId(),
				command.roleCode(),
				// El actor del evento es quien acepta: es quien produjo el hecho. Quien lo
				// decidio va en los detalles, que es donde se puede distinguir una cosa de la
				// otra sin inventar un actor que no ejecuto nada.
				command.accountId(),
				"Aceptacion de la invitacion " + command.invitacionId(),
				detalles);
	}

	/**
	 * Exige que el tenant tenga cupo de miembros en su plan.
	 *
	 * <p><b>Esto no estaba y el limite ya existia.</b> {@code MAX_MIEMBROS_ACTIVOS} esta en
	 * {@code LimitCode} desde 00.01 y {@code TenantUsageCounter} sabe contarlo desde 01.03, pero
	 * ninguna alta lo consultaba: un plan que declaraba cinco miembros admitia quinientos. Se
	 * cierra en 02.03 porque es la etapa que convierte el alta de colaboradores en un flujo real
	 * —hasta ahora era un endpoint que solo servia para cuentas ya registradas— y porque dejar
	 * la invitacion sin gate seria publicar la via por la que el limite se evade.
	 *
	 * <p>Se llama <b>antes</b> de {@link #bloquearTenant} porque el gate es quien toma el
	 * bloqueo de {@code subscription}, que es el primero del orden del sistema. Invertirlo
	 * tomaria los dos bloqueos al reves y reintroduciria el deadlock que 02.01 documento.
	 *
	 * <p>Corre con {@code Propagation.MANDATORY} dentro de la transaccion de quien llama, que
	 * <b>tiene que estar en READ COMMITTED</b>: en REPEATABLE READ el conteo lee de un snapshot
	 * anterior al bloqueo y el limite se viola en silencio. Los dos metodos que llaman aca lo
	 * declaran; {@code PlanGateService.verificarIsolation} lo comprueba en tiempo de ejecucion.
	 *
	 * @throws com.akine.organization.domain.exception.PlanLimitExceededException si no hay cupo
	 */
	private void exigirCupoDeMiembros(long organizationId) {
		planGate.evaluateCreationAndLock(
				organizationId,
				LimitCode.MAX_MIEMBROS_ACTIVOS,
				() -> usageCounter.count(LimitCode.MAX_MIEMBROS_ACTIVOS, organizationId));
	}

	/**
	 * Persiste el vinculo y lo audita. Es el tronco comun del alta directa y de la aceptacion.
	 *
	 * <p>Asume que el tenant ya esta bloqueado y que la autorizacion —la que corresponda a cada
	 * camino— ya ocurrio. No autoriza nada por su cuenta: si lo hiciera, la aceptacion de una
	 * invitacion tendria que inventarse un permiso que el invitado no tiene.
	 */
	private long persistirVinculo(
			long organizationId,
			long accountId,
			Long consultorioId,
			String roleCode,
			long actorAccountId,
			String motivo,
			Map<String, String> detalles) {

		RoleCode rol = rolValido(roleCode);
		validarSede(organizationId, consultorioId);

		Instant ahora = Instant.now();
		Membership membership = new Membership(
				organizationId, consultorioId, accountId, rol, false, ahora);

		Membership persistida;
		try {
			// saveAndFlush: la clave duplicada tiene que aparecer ACA y no al commit, donde el
			// catch ya no la ve y el advice generico devuelve 500.
			persistida = membershipRepository.saveAndFlush(membership);
		} catch (DataIntegrityViolationException claveDuplicada) {
			// Y desde aca NO se vuelve a tocar la sesion JPA: ni auditoria, ni lecturas. Una
			// sesion reusada despues de un flush fallido tira AssertionFailure.
			log.info("Alta de membership rechazada por clave duplicada: organizationId={} accountId={}",
					organizationId, accountId);
			throw new MembershipAlreadyExistsException(organizationId, consultorioId);
		}

		auditar(AuditEvents.MEMBERSHIP_CREATED, AuditEvents.ENTITY_MEMBERSHIP, persistida,
				actorAccountId, null, rol.name(), motivo, detalles, ahora);

		return persistida.getId();
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Cambia el rol y/o el alcance de una membership (RF-M02-004).
	 *
	 * <p>Los dos cambios se auditan por separado ({@code MEMBERSHIP_ROLE_CHANGED} y
	 * {@code MEMBERSHIP_SCOPE_CHANGED}): mover a alguien de sede y cambiarle lo que puede hacer
	 * son dos decisiones distintas, y mezclarlas en un evento hace la auditoria ilegible.
	 *
	 * @param nuevoRol         rol destino, o {@code null} para no tocarlo
	 * @param nuevaSede        sede destino; se aplica solo si {@code cambiarSede} es {@code true},
	 *                         porque {@code null} es un valor legitimo (alcance organizacion)
	 * @throws MembershipNotActiveException si la membership no esta {@code ACTIVA} (409)
	 * @throws LastAdminException si el cambio dejaria al tenant sin administradores (409)
	 * @throws SelfRevokeNotAllowedException si el actor se quitaria su ultimo rol administrativo (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MembershipView changeRole(
			OperatingActor actor,
			long organizationId,
			long membershipId,
			String nuevoRol,
			boolean cambiarSede,
			Long nuevaSede,
			String motivo) {

		bloquearTenant(organizationId);
		exigirMotivo(motivo, "el cambio de rol de un colaborador");

		Membership membership = cargar(organizationId, membershipId);
		exigirSobreLaMembership(actor, organizationId, membership);
		exigirActiva(membership, MembershipEstado.ACTIVA);

		Instant ahora = Instant.now();

		if (nuevoRol != null) {
			RoleCode destino = rolValido(nuevoRol);
			RoleCode anterior = membership.getRoleCode();
			if (destino != anterior) {
				// El cambio de rol puede sacar al ultimo administrador tan efectivamente como
				// una revocacion: si el destino no administra, es la misma carrera.
				if (!ROLES_ADMINISTRATIVOS.contains(destino)) {
					exigirQueNoSeaElUltimoAdmin(actor, organizationId, membership, ahora);
				}
				membership.changeRole(destino);
				auditar(AuditEvents.MEMBERSHIP_ROLE_CHANGED, AuditEvents.ENTITY_MEMBERSHIP,
						membership, actor.accountId(), anterior.name(), destino.name(), motivo,
						Map.of(), ahora);
			}
		}

		if (cambiarSede) {
			Long anterior = membership.getConsultorioId();
			if (!java.util.Objects.equals(anterior, nuevaSede)) {
				validarSede(organizationId, nuevaSede);
				membership.changeScope(nuevaSede);
				Map<String, String> detalles = new LinkedHashMap<>();
				detalles.put("consultorioAnterior", String.valueOf(anterior));
				detalles.put("consultorioNuevo", String.valueOf(nuevaSede));
				auditar(AuditEvents.MEMBERSHIP_SCOPE_CHANGED, AuditEvents.ENTITY_MEMBERSHIP,
						membership, actor.accountId(), null, null, motivo, detalles, ahora);
			}
		}

		return conCuenta(membershipRepository.save(membership));
	}

	/**
	 * Suspende temporalmente el vinculo. Motivo obligatorio.
	 *
	 * <p>Suspender saca a la persona igual que revocar mientras dura, asi que pasa por los
	 * mismos invariantes: no puede dejar al tenant sin administradores ni ser el ultimo rol
	 * administrativo del propio actor. Tratarlo como "mas suave" seria dejar abierta la misma
	 * puerta con otro nombre.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MembershipView suspend(
			OperatingActor actor, long organizationId, long membershipId, String motivo) {

		bloquearTenant(organizationId);
		exigirMotivo(motivo, "la suspension de un colaborador");

		Membership membership = cargar(organizationId, membershipId);
		exigirSobreLaMembership(actor, organizationId, membership);
		exigirActiva(membership, MembershipEstado.SUSPENDIDA);
		exigirQueNoSeaElFundadorAjeno(actor, membership);

		Instant ahora = Instant.now();
		exigirQueNoSeaElUltimoAdmin(actor, organizationId, membership, ahora);

		membership.suspender();
		auditar(AuditEvents.MEMBERSHIP_SUSPENDED, AuditEvents.ENTITY_MEMBERSHIP, membership,
				actor.accountId(), MembershipEstado.ACTIVA.name(),
				MembershipEstado.SUSPENDIDA.name(), motivo, Map.of(), ahora);

		return conCuenta(membershipRepository.save(membership));
	}

	/** Devuelve a {@code ACTIVA} un vinculo suspendido. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MembershipView reactivate(
			OperatingActor actor, long organizationId, long membershipId, String motivo) {

		bloquearTenant(organizationId);

		Membership membership = cargar(organizationId, membershipId);
		exigirSobreLaMembership(actor, organizationId, membership);
		if (membership.getEstado() != MembershipEstado.SUSPENDIDA) {
			throw new MembershipNotActiveException(
					membershipId, membership.getEstado(), MembershipEstado.ACTIVA);
		}

		Instant ahora = Instant.now();
		membership.reactivar();
		auditar(AuditEvents.MEMBERSHIP_REACTIVATED, AuditEvents.ENTITY_MEMBERSHIP, membership,
				actor.accountId(), MembershipEstado.SUSPENDIDA.name(),
				MembershipEstado.ACTIVA.name(), motivo, Map.of(), ahora);

		return conCuenta(membershipRepository.save(membership));
	}

	/**
	 * Revoca el vinculo. Estado terminal, motivo obligatorio, <b>sin borrado</b> (RN-M05-003).
	 *
	 * <p>Es la operacion con los tres invariantes a la vez: ultimo admin, self-revoke y
	 * proteccion del fundador. Los tres se verifican DESPUES del bloqueo del tenant, que es lo
	 * unico que hace que el conteo signifique algo bajo concurrencia.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MembershipView revoke(
			OperatingActor actor, long organizationId, long membershipId, String motivo) {

		bloquearTenant(organizationId);
		exigirMotivo(motivo, "la revocacion de un colaborador");

		Membership membership = cargar(organizationId, membershipId);
		exigirSobreLaMembership(actor, organizationId, membership);

		if (membership.getEstado() == MembershipEstado.REVOCADA) {
			throw new MembershipNotActiveException(
					membershipId, membership.getEstado(), MembershipEstado.REVOCADA);
		}
		exigirQueNoSeaElFundadorAjeno(actor, membership);

		Instant ahora = Instant.now();
		exigirQueNoSeaElUltimoAdmin(actor, organizationId, membership, ahora);

		// RN-M05-004: lo que queda pendiente NO impide la revocacion, pero tiene que quedar
		// escrito. Ver `ColaboradorDesvinculacionProbe` para por que esta sonda informa y la de
		// consultorios bloquea, que son dos reglas distintas y es facil confundirlas.
		ColaboradorDesvinculacionProbe.Impacto impacto =
				impactoDe(organizationId, membership, ahora);

		MembershipEstado anterior = membership.getEstado();
		membership.revocar(actor.accountId(), motivo, ahora);
		auditar(AuditEvents.MEMBERSHIP_REVOKED, AuditEvents.ENTITY_MEMBERSHIP, membership,
				actor.accountId(), anterior.name(), MembershipEstado.REVOCADA.name(), motivo,
				detallesDelImpacto(impacto), ahora);

		return conCuenta(membershipRepository.save(membership));
	}

	/**
	 * Que quedaria pendiente si se desvinculara a este colaborador (RN-M05-004).
	 *
	 * <p>Es una <b>lectura</b> y por eso pide {@code colaborador:read} y no
	 * {@code colaborador:manage}: sirve para decidir, y quien decide suele mirar antes de tener
	 * el permiso de ejecutar. Exigir el permiso de escritura convertiria el analisis previo en
	 * algo que solo puede ver quien ya podia hacerlo sin mirar.
	 *
	 * <p>Responden dos sondas: la de turnos pendientes de {@code scheduling} (paquete E-1), que va
	 * primero, y la de bloques de disponibilidad de {@code resource}. Ver {@link #impactoDe}.
	 */
	@Transactional(readOnly = true)
	public ColaboradorDesvinculacionProbe.Impacto desvinculacionImpacto(
			OperatingActor actor, long organizationId, long membershipId) {

		exigirEnLectura(actor, organizationId, PermissionCode.COLABORADOR_READ, membershipId);
		Membership membership = cargar(organizationId, membershipId);
		return impactoDe(organizationId, membership, Instant.now());
	}

	/**
	 * Recorre las sondas y devuelve el primer impacto con contenido.
	 *
	 * <p>El primero y no la suma: sumar "14 turnos" con "3 sesiones abiertas" da 17 de nada.
	 * Desde E-1 hay dos —turnos y bloques de disponibilidad— y el orden lo fija {@code @Order} en
	 * la de turnos: cuando hay turnos, se muestran ellos y los bloques no. Mostrar las dos a la
	 * vez exige que la respuesta de la API pase a ser un array, y eso es cambio de contrato.
	 */
	private ColaboradorDesvinculacionProbe.Impacto impactoDe(
			long organizationId, Membership membership, Instant at) {

		for (ColaboradorDesvinculacionProbe sonda : desvinculacionProbes) {
			ColaboradorDesvinculacionProbe.Impacto impacto = sonda.pendingWorkOn(
					organizationId, membership.getId(), membership.getAccountId(), at);
			if (impacto != null && impacto.hayAlgo()) {
				return impacto;
			}
		}
		return ColaboradorDesvinculacionProbe.Impacto.ninguno();
	}

	/** Detalles del evento de revocacion. Vacio cuando no hay nada pendiente. */
	private static Map<String, String> detallesDelImpacto(
			ColaboradorDesvinculacionProbe.Impacto impacto) {

		if (!impacto.hayAlgo()) {
			return Map.of();
		}
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("pendienteTipo", impacto.tipo());
		detalles.put("pendienteCount", String.valueOf(impacto.count()));
		if (impacto.desde() != null) {
			detalles.put("pendienteDesde", impacto.desde().toString());
		}
		return detalles;
	}

	// =================================================================================
	// Permisos adicionales
	// =================================================================================

	/**
	 * Otorga un permiso adicional a una membership (matriz §3, "No por defecto").
	 *
	 * @throws UnknownPermissionCodeException si el codigo no esta en el catalogo o no es
	 *         otorgable en esta fase (400)
	 * @throws GrantAlreadyActiveException si ya hay uno vigente de ese permiso (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MembershipGrantView assignGrant(
			OperatingActor actor,
			long organizationId,
			long membershipId,
			String permissionCode,
			String reason,
			Instant validUntil) {

		bloquearTenant(organizationId);
		exigirMotivo(reason, "el otorgamiento de un permiso adicional");

		Membership membership = cargar(organizationId, membershipId);
		exigirSobreLaMembership(actor, organizationId, membership);
		exigirActiva(membership, MembershipEstado.ACTIVA);

		PermissionCode permiso = permisoOtorgable(permissionCode);
		// G-5: el codigo solo no alcanza. Una celda "No" de la matriz no es otorgable por
		// membership (§3), aunque el mismo codigo si lo sea para otro rol.
		if (!RolePermissions.otorgableComoGrant(membership.getRoleCode(), permiso)) {
			throw new UnknownPermissionCodeException(permissionCode,
					"La matriz de permisos no admite otorgar ese permiso al rol "
							+ membership.getRoleCode().name());
		}
		Instant ahora = Instant.now();

		MembershipGrant grant = new MembershipGrant(
				organizationId, membershipId, permiso, actor.accountId(), reason, ahora, validUntil);

		MembershipGrant persistido;
		try {
			persistido = grantRepository.saveAndFlush(grant);
		} catch (DataIntegrityViolationException claveDuplicada) {
			// El unique uk_membership_grant_activo decide, no un chequeo previo: dos admins
			// otorgando a la vez leerian los dos "no existe". Sin tocar JPA de nuevo.
			log.info("Grant rechazado por duplicado: membershipId={} permiso={}",
					membershipId, permiso.code());
			throw new GrantAlreadyActiveException(permiso.code(), membershipId);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("permissionCode", permiso.code());
		auditar(AuditEvents.GRANT_ASSIGNED, AuditEvents.ENTITY_MEMBERSHIP_GRANT, membership,
				actor.accountId(), null, permiso.code(), reason, detalles, ahora);

		return MembershipGrantView.de(persistido);
	}

	/**
	 * Da de baja un permiso adicional. Baja logica: la fila queda con su autor y su motivo.
	 *
	 * @throws MembershipNotAccessibleException si la membership no es alcanzable (404)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void revokeGrant(
			OperatingActor actor,
			long organizationId,
			long membershipId,
			String permissionCode,
			String motivo) {

		bloquearTenant(organizationId);

		Membership membership = cargar(organizationId, membershipId);
		exigirSobreLaMembership(actor, organizationId, membership);

		PermissionCode permiso = PermissionCode.desde(permissionCode)
				.orElseThrow(() -> new UnknownPermissionCodeException(
						permissionCode, "El codigo de permiso no existe en el catalogo"));

		Optional<MembershipGrant> vigente = grantRepository
				.findByMembershipIdAndPermissionCodeAndActiveTrue(membershipId, permiso);
		if (vigente.isEmpty()) {
			// Idempotente hacia el mismo resultado: si ya no esta, esta revocado. Lanzar un 409
			// aca obligaria al cliente a distinguir dos situaciones indistinguibles para el
			// usuario ("no lo tenia" y "se lo acaban de quitar").
			log.debug("Revocacion de grant sin efecto: membershipId={} permiso={}",
					membershipId, permiso.code());
			return;
		}

		Instant ahora = Instant.now();
		MembershipGrant grant = vigente.get();
		grant.revoke(actor.accountId(), motivo, ahora);
		grantRepository.save(grant);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("permissionCode", permiso.code());
		auditar(AuditEvents.GRANT_REVOKED, AuditEvents.ENTITY_MEMBERSHIP_GRANT, membership,
				actor.accountId(), permiso.code(), null, motivo, detalles, ahora);
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * Cierra las dos carreras que dejan un tenant administrativamente muerto.
	 *
	 * <p><b>Ultimo admin.</b> Dos {@code ORG_ADMIN}, A y B; en el mismo instante A revoca a B y
	 * B revoca a A. Cada transaccion cuenta "queda otro admin ademas del que estoy revocando",
	 * las dos ven al otro, las dos pasan, y la organizacion queda sin ninguno. No hay reparacion
	 * desde la aplicacion: no queda nadie con permiso para asignar un admin, y con la invitacion
	 * fuera de alcance (D-1) tampoco queda el rodeo de invitar a alguien. Se cierra con el
	 * bloqueo del tenant (paso 1 de toda mutacion) MAS el conteo con {@code FOR SHARE}: el
	 * bloqueo serializa el acceso, la lectura con lock resuelve la visibilidad, y hacen falta
	 * los dos.
	 *
	 * <p><b>Self-revoke.</b> Se rechaza aunque queden otros administradores, si lo que el actor
	 * se quita es su ultimo rol administrativo: despues de la operacion no puede deshacerla, y
	 * una accion irreversible por distraccion no deberia ser un PATCH cualquiera. El camino
	 * correcto es que otro admin lo revoque, o promover a alguien antes.
	 *
	 * <p>El conteo EXCLUYE la membership objetivo, y eso tambien es parte del arreglo: asi el
	 * lock compartido nunca cae sobre la fila que despues se actualiza, y no hay escalada
	 * S &rarr; X sobre una fila que otra transaccion tiene en compartido — el segundo bug de
	 * concurrencia de 01.01/01.02.
	 */
	private void exigirQueNoSeaElUltimoAdmin(
			OperatingActor actor, long organizationId, Membership objetivo, Instant ahora) {

		if (!ROLES_ADMINISTRATIVOS.contains(objetivo.getRoleCode())) {
			return;
		}

		if (objetivo.getAccountId() == actor.accountId()) {
			boolean leQuedaOtroRolAdministrativo = membershipRepository
					.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
							organizationId, actor.accountId())
					.stream()
					.filter(m -> !m.getId().equals(objetivo.getId()))
					.filter(m -> m.isValidAt(ahora))
					.anyMatch(m -> ROLES_ADMINISTRATIVOS.contains(m.getRoleCode()));
			if (!leQuedaOtroRolAdministrativo) {
				throw new SelfRevokeNotAllowedException(actor.accountId());
			}
		}

		if (objetivo.getRoleCode() != RoleCode.ORG_ADMIN) {
			// El invariante es sobre la ORGANIZACION, no sobre el consultorio: una sede puede
			// quedarse sin CONSULTORIO_ADMIN, la administra el ORG_ADMIN. 01.03 no inventa un
			// segundo invariante que la matriz no pide.
			return;
		}

		long quedan = membershipRepository.countActiveOrgAdminsForShare(
				organizationId, ahora, objetivo.getId());
		if (quedan == 0) {
			log.info("Operacion rechazada por dejar al tenant sin administradores: organizationId={}",
					organizationId);
			throw new LastAdminException(organizationId);
		}
	}

	/**
	 * Impide que un administrador que no es el fundador lo desvincule.
	 *
	 * <p>El invariante se lo asigno a esta etapa el comentario de {@code is_founder} en V3. El
	 * fundador si puede revocarse a si mismo, sujeto a los otros dos invariantes; y un
	 * {@code PLATFORM_ADMIN} <b>con acceso de soporte vigente</b> tambien puede, porque es el
	 * unico rescate posible de un tenant cuyo fundador ya no esta — y queda auditado con
	 * {@code SUPPORT_ACCESS_USED}. Tener el rol de plataforma sin acceso de soporte no alcanza:
	 * seria una puerta trasera permanente sobre el vinculo mas sensible del tenant.
	 */
	private void exigirQueNoSeaElFundadorAjeno(OperatingActor actor, Membership objetivo) {
		if (!objetivo.isFounder()) {
			return;
		}
		if (objetivo.getAccountId() == actor.accountId()) {
			return;
		}
		if (actor.platformAdmin()
				&& permissionEvaluator.hasSupportAccess(
						actor.accountId(), objetivo.getOrganizationId(), Instant.now())) {
			return;
		}
		log.info("Intento de desvincular al fundador por otro administrador: membershipId={}",
				objetivo.getId());
		throw new FounderRevocationNotAllowedException(objetivo.getId());
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	/**
	 * Toma el punto de serializacion del tenant. <b>Primera sentencia de toda mutacion.</b>
	 *
	 * <p>Ver el javadoc de la clase: la fila es la de {@code subscription} y no la de
	 * {@code organization}, y eso evita el deadlock contra el alta de sede de AKINE-02.01.
	 */
	private void bloquearTenant(long organizationId) {
		subscriptionRepository.findByOrganizationIdForUpdate(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	/**
	 * Exige {@code colaborador:manage} sobre una membership ya cargada.
	 *
	 * <p><b>No se pasa {@code targetAccountId}, y eso es deliberado.</b> Ese parametro existe
	 * para que el evaluador verifique que la cuenta objetivo pertenece a la organizacion del
	 * actor (ADR-0019) cuando lo unico que se tiene es un id de cuenta suelto — el caso de
	 * {@code AccountAdminService}. Aca la membership ya se cargo por {@code (id, organizationId)},
	 * que es una comprobacion <b>mas fuerte</b>: no solo dice que la persona esta en la
	 * organizacion, dice que ESTA fila lo esta.
	 *
	 * <p>Pasarlo ademas rompia un caso real: el evaluador exige que el objetivo tenga una
	 * membership <b>vigente</b>, asi que operar sobre una que ya fue revocada respondia 404 en
	 * vez del 409 {@code membership-not-active} que corresponde. Un administrador que ve la fila
	 * revocada en su propio listado y recibe "no existe" al tocarla no tiene forma de entender
	 * que paso.
	 */
	private void exigirSobreLaMembership(
			OperatingActor actor, long organizationId, Membership membership) {
		exigir(actor, organizationId, PermissionCode.COLABORADOR_MANAGE, membership.getId());
	}

	/**
	 * Exige el permiso, propagando la decision.
	 *
	 * <p>Cuando la concesion se apoya en un acceso de soporte vigente, se registra
	 * {@code SUPPORT_ACCESS_USED}: la matriz §7 pide auditar CADA operacion amparada por
	 * soporte, no solo su otorgamiento.
	 */
	private void exigir(
			OperatingActor actor, long organizationId, PermissionCode permiso, Long entidad) {

		exigirEnAlcance(actor, organizationId, permiso, actor.consultorioId(), entidad);
	}

	/**
	 * Exige el permiso <b>sobre un alcance declarado</b>, que no siempre es el del actor.
	 *
	 * <p>Existe por el alta directa. Ahi la pregunta no es "¿que puede hacer este actor donde
	 * esta parado?" sino "¿puede administrar colaboradores <b>en la sede a la que quiere
	 * vincular</b>?": un administrador de la sede A no tiene por que dar de alta gente en la B.
	 *
	 * <p><b>Por que un metodo aparte y no meter esa sede en el {@link OperatingActor}.</b> Es lo
	 * que se hacia, y era correcto en el resultado pero mentia en el medio: el javadoc de ese
	 * record dice que {@code consultorioId} sale del contexto que {@code TenantContextFilter} ya
	 * revalido, <b>nunca</b> de un parametro del cliente. Meter ahi el alcance pedido en el
	 * cuerpo del request dejaba un campo de seguridad diciendo una cosa y conteniendo otra, y el
	 * proximo que lo leyera iba a asumir la garantia equivocada. El alcance destino es un dato
	 * distinto y ahora se llama distinto.
	 *
	 * @param alcance sede sobre la que se evalua el permiso. {@code null} evalua alcance
	 *                organizacion
	 */
	private void exigirEnAlcance(
			OperatingActor actor,
			long organizationId,
			PermissionCode permiso,
			Long alcance,
			Long entidad) {

		Instant ahora = Instant.now();
		// El objetivo del evaluador va SIEMPRE en null desde este servicio: ver
		// exigirSobreLaMembership. `entidad` es solo el id que se registra si la operacion queda
		// amparada por un acceso de soporte.
		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso.code(), organizationId,
				alcance, null, ahora));

		if (decision.viaSupportAccess()) {
			auditTrail.record(usoDeSoporte(actor, organizationId, alcance, permiso, entidad, ahora));
		}
	}

	/**
	 * Exige el permiso <b>en una lectura</b>, auditando el uso de soporte por afuera.
	 *
	 * <p>Es {@link #exigir} con una sola diferencia, y no es cosmetica: la entrada
	 * {@code SUPPORT_ACCESS_USED} se escribe por {@link SupportAccessReadAuditor}, que abre su
	 * propia transaccion. Las tres lecturas de este servicio son {@code readOnly = true} y una
	 * transaccion de solo lectura deja el flush de Hibernate en MANUAL: la fila de auditoria
	 * escrita ahi adentro <b>no llega nunca a la base</b> y nadie se entera. Y aunque el
	 * {@code readOnly} se sacara, {@code find} y {@code grants} lanzan 404 despues de evaluar el
	 * permiso, con lo que el rollback se llevaria igual la fila. El razonamiento completo, con
	 * las alternativas descartadas, esta en el javadoc de {@link SupportAccessReadAuditor}.
	 */
	private void exigirEnLectura(
			OperatingActor actor, long organizationId, PermissionCode permiso, Long entidad) {

		Instant ahora = Instant.now();
		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso.code(), organizationId,
				actor.consultorioId(), null, ahora));

		if (decision.viaSupportAccess()) {
			supportAccessReadAuditor.record(usoDeSoporte(
					actor, organizationId, actor.consultorioId(), permiso, entidad, ahora));
		}
	}

	/**
	 * Arma la entrada {@code SUPPORT_ACCESS_USED} delegando en la UNICA definicion del modulo.
	 *
	 * <p>Estaba definida aca y otra vez en {@code ConsultorioService}, con formas parecidas
	 * pero no identicas, y otros cuatro puntos de acceso ni la escribian. Vive ahora en
	 * {@link AuditEvents}: si la lectura y la mutacion registraran cosas distintas, la
	 * investigacion de un incidente tendria que saber por cual de los dos entro cada fila.
	 */
	private static AuditEntry usoDeSoporte(
			OperatingActor actor,
			long organizationId,
			Long alcance,
			PermissionCode permiso,
			Long entidad,
			Instant ahora) {

		return AuditEvents.usoDeSoporte(
				organizationId, alcance, actor.accountId(), permiso.code(), entidad, ahora);
	}

	/**
	 * Carga la membership acotada al tenant.
	 *
	 * <p>Se busca por {@code (id, organizationId)} y nunca por id pelado: un id de otro tenant no
	 * resuelve y responde 404, igual que uno inexistente. Distinguirlos permitiria enumerar a
	 * los colaboradores de otros centros.
	 */
	/**
	 * Vista de UNA membership con la identidad de su cuenta resuelta.
	 *
	 * <p>La usan tambien las mutaciones: si el PATCH devolviera la fila sin nombre, la pantalla
	 * que acaba de cambiar un rol tendria que recargar el listado entero solo para no mostrar un
	 * numero donde antes habia una persona.
	 */
	private MembershipView conCuenta(Membership membership) {
		return MembershipView.de(
				membership,
				accountDirectory.identidadesDe(List.of(membership.getAccountId()))
						.get(membership.getAccountId()));
	}

	private Membership cargar(long organizationId, long membershipId) {
		return membershipRepository.findByIdAndOrganizationId(membershipId, organizationId)
				.orElseThrow(() -> new MembershipNotAccessibleException(membershipId));
	}

	private void exigirActiva(Membership membership, MembershipEstado destino) {
		if (membership.getEstado() != MembershipEstado.ACTIVA) {
			throw new MembershipNotActiveException(
					membership.getId(), membership.getEstado(), destino);
		}
	}

	/**
	 * Valida que la sede exista y sea del tenant.
	 *
	 * <p>Sin esto, una membership podria quedar acotada a un consultorio de otra organizacion:
	 * la clave foranea impediria una sede inexistente, no una ajena.
	 */
	private void validarSede(long organizationId, Long consultorioId) {
		if (consultorioId == null) {
			return;
		}
		consultorioRepository
				.findByIdAndOrganizationIdAndActiveTrue(consultorioId, organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	private static RoleCode rolValido(String roleCode) {
		RoleCode rol;
		try {
			rol = RoleCode.valueOf(roleCode);
		} catch (IllegalArgumentException | NullPointerException desconocido) {
			throw new UnknownPermissionCodeException(
					roleCode, "El rol indicado no existe en el catalogo de la matriz");
		}
		if (rol == RoleCode.PLATFORM_ADMIN) {
			// Matriz §1.3: PLATFORM_ADMIN no tiene membership en ninguna organizacion. Lo
			// impide tambien un CHECK en la base; aca se impide antes para que el error sea
			// comprensible en vez de una violacion de constraint.
			throw new UnknownPermissionCodeException(
					roleCode, "PLATFORM_ADMIN no es un rol de membership: vive en platform_role");
		}
		return rol;
	}

	private static PermissionCode permisoOtorgable(String permissionCode) {
		PermissionCode permiso = PermissionCode.desde(permissionCode)
				.orElseThrow(() -> new UnknownPermissionCodeException(
						permissionCode, "El codigo de permiso no existe en el catalogo"));
		if (!RolePermissions.otorgablesComoGrant().contains(permiso)) {
			throw new UnknownPermissionCodeException(permissionCode,
					"Ese permiso no es otorgable como permiso adicional en esta fase");
		}
		return permiso;
	}

	private static void exigirMotivo(String motivo, String operacion) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para " + operacion
							+ ": sin el, la auditoria no responde por que seis meses despues");
		}
	}

	private void auditar(
			String eventType,
			String entityType,
			Membership membership,
			long actorAccountId,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				membership.getOrganizationId(),
				membership.getConsultorioId(),
				actorAccountId,
				eventType,
				entityType,
				membership.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	/**
	 * Roles que en la matriz §6 tienen {@code colaborador:manage} en su asignacion base.
	 *
	 * <p>Se deriva de la matriz en vez de escribirse a mano para que una celda que se agregue o
	 * se quite alli se refleje sola en el invariante de self-revoke. Una lista literal
	 * divergiria en el primer cambio y nadie lo notaria hasta que alguien quedara encerrado
	 * afuera de su propia organizacion.
	 */
	private static Set<RoleCode> rolesConGestionDeColaboradores() {
		EnumSet<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
		for (RoleCode rol : RoleCode.values()) {
			if (rol == RoleCode.PLATFORM_ADMIN) {
				continue;
			}
			RolePermissions.baseScope(rol, PermissionCode.COLABORADOR_MANAGE)
					.ifPresent(alcance -> roles.add(rol));
		}
		return Set.copyOf(roles);
	}
}
