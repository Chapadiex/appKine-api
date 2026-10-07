package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.MembershipSelection;
import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.PermissionScope;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.RolePermissions;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.domain.port.MembershipGrantRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.organization.domain.port.SupportAccessRepositoryPort;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * El evaluador de la matriz de permisos. Reemplaza a la autorizacion gruesa e interina de
 * AKINE-01.01/01.02.
 *
 * <h2>Por que vive en {@code organization}</h2>
 *
 * <p>Es el unico modulo que cumple las tres condiciones a la vez: es dueño de los datos que la
 * evaluacion necesita ({@code membership}, {@code membership_grant}, {@code platform_role},
 * {@code support_access}, {@code consultorio}), es dueño de la semantica ({@code RoleCode} y
 * {@code RolePermissions} viven en su dominio, y la matriz es el catalogo de ese enum) y no
 * cierra ningun ciclo.
 *
 * <p>Un modulo {@code authorization} aparte no funcionaria: necesitaria leer {@code membership}
 * —{@code authorization -> organization.spi}— y {@code organization} necesitaria autorizar sus
 * propios endpoints —{@code organization -> authorization.spi}—. Ciclo. La unica salida seria
 * que el evaluador recibiera la membership por parametro, con lo que la regla "revalidar contra
 * la base" quedaria delegada a cada llamador: exactamente lo que se quiere evitar.
 *
 * <h2>El algoritmo, y las tres decisiones que lo definen</h2>
 *
 * <p><b>1. Cual es la membership que gobierna.</b> No se unen los permisos de todas las
 * memberships del actor: se elige UNA con {@link MembershipSelection} y se decide con ella.
 * Para una decision sobre una sede gana la mas especifica; para una decision sobre la
 * organizacion entera hace falta una membership de alcance organizacion. Los dos criterios son
 * fail-closed y ya estaban centralizados desde V10; esta etapa los usa, no los reescribe.
 * Unir permisos habria dado el efecto contrario: alguien con una membership de {@code
 * PROFESIONAL} en una sede y otra de {@code ORG_ADMIN} en la organizacion administraria esa
 * sede pese a que la fila especifica existe justamente para decir otra cosa.
 *
 * <p><b>2. El alcance se verifica aparte del permiso.</b> Tener {@code colaborador:manage} y
 * tenerlo SOBRE ESTE recurso son dos preguntas. La segunda es la que impide que un
 * {@code CONSULTORIO_ADMIN} toque colaboradores de otra sede.
 *
 * <p><b>3. {@code PLATFORM_ADMIN} y el acceso de soporte.</b> La matriz seccion 6 le da
 * "Global" a las acciones administrativas del tenant (su contrato: alta, plan, suscripcion,
 * colaboradores) y "Soporte"/"Restringido" a las que tocan datos de personas. Esta clase
 * respeta esa diferencia literalmente: los alcances {@code SOPORTE} y {@code RESTRINGIDO}
 * <b>exigen</b> un {@code support_access} vigente para esa organizacion; {@code GLOBAL} no.
 * Cuando un administrador de plataforma opera dentro de un tenant y ademas tiene un acceso de
 * soporte vigente, la decision vuelve con {@code viaSupportAccess = true} y el llamador debe
 * registrar {@code SUPPORT_ACCESS_USED} — la matriz seccion 7 exige auditar CADA operacion
 * amparada, no solo el otorgamiento.
 *
 * <h2>Dos garantias que no se negocian</h2>
 *
 * <p><b>Sin cache.</b> Cada evaluacion lee la base. La ventana de revocacion es cero: una
 * membership revocada deja de habilitar en la operacion siguiente. Si algun dia el SLO lo
 * exige, es un ADR nuevo (igual que dice hoy el javadoc de {@code MembershipDirectory}).
 *
 * <p><b>Se evalua dentro de la transaccion de negocio.</b> Las anotaciones de aca son
 * {@code readOnly} y se unen a la transaccion del llamador cuando la hay; no abren una propia
 * ni la fuerzan. Para las mutaciones que ademas tocan memberships, el llamador evalua DESPUES
 * de tomar el bloqueo del tenant, y ahi la ventana entre "tenia permiso" y "commiteo" es cero.
 */
@Service
public class PermissionEvaluatorService implements PermissionEvaluator, PermissionGuard {

	private static final Logger log = LoggerFactory.getLogger(PermissionEvaluatorService.class);

	private final MembershipRepositoryPort membershipRepository;
	private final MembershipGrantRepositoryPort grantRepository;
	private final PlatformRoleRepositoryPort platformRoleRepository;
	private final SupportAccessRepositoryPort supportAccessRepository;
	private final PermissionDenialAuditor denialAuditor;

	public PermissionEvaluatorService(
			MembershipRepositoryPort membershipRepository,
			MembershipGrantRepositoryPort grantRepository,
			PlatformRoleRepositoryPort platformRoleRepository,
			SupportAccessRepositoryPort supportAccessRepository,
			PermissionDenialAuditor denialAuditor) {
		this.membershipRepository = membershipRepository;
		this.grantRepository = grantRepository;
		this.platformRoleRepository = platformRoleRepository;
		this.supportAccessRepository = supportAccessRepository;
		this.denialAuditor = denialAuditor;
	}

	// =================================================================================
	// PermissionEvaluator
	// =================================================================================

	/**
	 * {@inheritDoc}
	 *
	 * <p>Un codigo de permiso desconocido <b>deniega</b> en vez de lanzar. Es una decision de
	 * seguridad, no de comodidad: un evaluador que explota ante un codigo mal escrito convierte
	 * un error de tipeo en un 500, y un evaluador que ante la duda concede es un agujero. Ante
	 * la duda, cerrado.
	 */
	@Override
	@Transactional(readOnly = true)
	public PermissionDecision evaluate(PermissionQuery query) {
		Optional<PermissionCode> permiso = PermissionCode.desde(query.permissionCode());
		if (permiso.isEmpty()) {
			log.warn("Permiso desconocido en una evaluacion: code={}", query.permissionCode());
			return PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
		}
		return evaluar(query, permiso.get());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<String> effectivePermissions(long accountId, long organizationId, Long consultorioId) {
		Instant ahora = Instant.now();
		Set<String> efectivos = new LinkedHashSet<>();

		if (isPlatformAdmin(accountId, ahora)) {
			boolean conSoporte = hasSupportAccess(accountId, organizationId, ahora);
			RolePermissions.baseOf(RoleCode.PLATFORM_ADMIN)
					.forEach((codigo, alcance) -> {
						if (alcance == PermissionScope.GLOBAL
								|| (alcance == PermissionScope.SOPORTE && conSoporte)) {
							efectivos.add(codigo.code());
						}
						// RESTRINGIDO nunca entra: exige ademas un permiso clinico explicito
						// que no existe hasta F4. Que no aparezca en la lista es correcto y es
						// lo que el frontend tiene que ver.
					});
			return Set.copyOf(efectivos);
		}

		Optional<Membership> gobernante = membershipGobernante(accountId, organizationId, consultorioId, ahora);
		if (gobernante.isEmpty()) {
			// Sin membership vigente que cubra el contexto no hay ningun permiso. El selector
			// del frontend interpreta el conjunto vacio como "elegi otro contexto", no como un
			// error.
			return Set.of();
		}

		Membership membership = gobernante.get();
		RolePermissions.baseOf(membership.getRoleCode()).forEach((codigo, alcance) -> {
			if (alcance != PermissionScope.RESTRINGIDO) {
				efectivos.add(codigo.code());
			}
		});
		grantsVigentes(membership, ahora).forEach(g -> efectivos.add(g.getPermissionCode().code()));
		return Set.copyOf(efectivos);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isPlatformAdmin(long accountId, Instant at) {
		return platformRoleRepository.findAllByAccountIdAndActiveTrue(accountId).stream()
				.anyMatch(rol -> rol.isValidAt(at));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasSupportAccess(long accountId, long organizationId, Instant at) {
		return supportAccessRepository
				.findAllByOrganizationIdAndAccountIdAndActiveTrue(organizationId, accountId).stream()
				.anyMatch(acceso -> acceso.isValidAt(at));
	}

	// =================================================================================
	// PermissionGuard
	// =================================================================================

	/**
	 * {@inheritDoc}
	 *
	 * <p>El mapeo del rechazo a una excepcion —y por lo tanto a un codigo HTTP— ocurre <b>aca y
	 * solo aca</b>. Si cada llamador tradujera su propio {@link DenialKind}, la regla
	 * "cross-tenant es 404" duraria hasta el tercer controller.
	 *
	 * <p><b>El rechazo por permiso se audita en una transaccion separada.</b> Escribirlo en la
	 * transaccion en curso y despues lanzar haria rollback de la propia fila de auditoria: la
	 * mutacion no ocurriria —correcto— y el rechazo tampoco quedaria registrado —incorrecto—.
	 * Es el mismo caso, y la misma solucion, que {@code PlanLimitRejectionAuditor}.
	 *
	 * <p>El rechazo por ALCANCE no se audita en la tabla, a proposito: registrarlo construiria
	 * dentro de {@code audit_event} el mismo padron de existencia de tenants ajenos que el 404
	 * uniforme existe para no entregar. Va al log, correlacionado por {@code traceId}.
	 */
	@Override
	@Transactional(readOnly = true)
	public PermissionDecision requirePermission(PermissionQuery query) {
		PermissionDecision decision = evaluate(query);
		if (decision.granted()) {
			return decision;
		}

		switch (decision.denial()) {
			case NO_CONTEXT -> {
				log.info("Operacion sin contexto de tenant: accountId={} permiso={}",
						query.accountId(), query.permissionCode());
				throw new AccessDeniedException(
						"La operacion requiere un contexto de trabajo activo");
			}
			case OUT_OF_SCOPE -> {
				log.info("Recurso fuera del alcance del actor: accountId={} organizationId={} permiso={}",
						query.accountId(), query.organizationId(), query.permissionCode());
				throw new OrganizationNotFoundException(query.organizationId());
			}
			default -> {
				log.info("Permiso denegado: accountId={} organizationId={} permiso={} motivo={}",
						query.accountId(), query.organizationId(), query.permissionCode(),
						decision.denial());
				denialAuditor.recordDenial(query, decision.denial());
				throw new PermissionDeniedException(
						query.permissionCode(), query.accountId(), query.organizationId());
			}
		}
	}

	// =================================================================================
	// El algoritmo
	// =================================================================================

	private PermissionDecision evaluar(PermissionQuery query, PermissionCode permiso) {
		Instant at = query.at();

		if (isPlatformAdmin(query.accountId(), at)) {
			return evaluarComoPlataforma(query, permiso, at);
		}

		Long organizationId = query.organizationId();
		if (organizationId == null) {
			// Autenticado pero sin haber elegido donde trabaja. Es 403 y jamas 401: el
			// interceptor del frontend borra el token ante cualquier 401 y el usuario entra en
			// un bucle de login del que no sale.
			return PermissionDecision.rechazada(DenialKind.NO_CONTEXT);
		}

		List<Membership> memberships = membershipRepository
				.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
						organizationId, query.accountId());
		boolean esMiembro = memberships.stream().anyMatch(m -> m.isValidAt(at));
		if (!esMiembro) {
			// No pertenece a la organizacion: para el, el recurso no existe. 404.
			return PermissionDecision.rechazada(DenialKind.OUT_OF_SCOPE);
		}

		if (!objetivoAlcanzable(query, at)) {
			// La matriz decide QUE puede hacer el actor; la membership del objetivo sigue
			// decidiendo SOBRE QUIEN (ADR-0019). Una cuenta de otra organizacion se responde
			// como inexistente, nunca como prohibida.
			return PermissionDecision.rechazada(DenialKind.OUT_OF_SCOPE);
		}

		Optional<Membership> gobernante =
				elegirGobernante(memberships, query.consultorioId(), at);
		if (gobernante.isEmpty()) {
			// Es miembro de la organizacion pero ninguna de sus memberships gobierna este
			// alcance —por ejemplo, un CONSULTORIO_ADMIN decidiendo sobre el tenant entero—.
			// El recurso existe para el, asi que 403 y no 404.
			return PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
		}

		Membership membership = gobernante.get();
		Optional<PermissionScope> base = RolePermissions.baseScope(membership.getRoleCode(), permiso);

		if (base.isPresent()) {
			PermissionScope alcance = base.get();
			if (alcance == PermissionScope.RESTRINGIDO || alcance == PermissionScope.SOPORTE) {
				// Solo PLATFORM_ADMIN tiene celdas asi en la matriz, y ya se resolvio arriba.
				// Llegar aca significaria que alguien le agrego una a un rol de tenant sin
				// definir como se otorga: fail-closed.
				return PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
			}
			if (alcanceCubre(alcance, membership, query.consultorioId())) {
				return PermissionDecision.concedida(alcance.name(), false);
			}
		}

		// El grant esta atado a la membership, asi que hereda su alcance: un grant sobre una
		// membership de sede no puede habilitar mas alla de esa sede.
		boolean porGrant = grantsVigentes(membership, at).stream()
				.anyMatch(g -> g.getPermissionCode() == permiso);
		if (porGrant) {
			PermissionScope alcanceDelGrant = membership.isOrganizationScoped()
					? PermissionScope.ORGANIZACION
					: PermissionScope.CONSULTORIO;
			if (alcanceCubre(alcanceDelGrant, membership, query.consultorioId())) {
				return PermissionDecision.concedida(alcanceDelGrant.name(), false);
			}
		}

		return PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
	}

	/**
	 * Decision para un administrador de plataforma.
	 *
	 * <p>Tres caminos, y la diferencia entre ellos es la que la matriz seccion 6 establece:
	 * <ul>
	 *   <li><b>{@code GLOBAL}</b> —administrar el contrato del tenant, sus colaboradores, su
	 *       suscripcion— se concede. Si ademas hay un acceso de soporte vigente sobre ese
	 *       tenant, la decision lo informa para que el llamador registre
	 *       {@code SUPPORT_ACCESS_USED}.</li>
	 *   <li><b>{@code SOPORTE}</b> —los datos de personas del tenant— exige acceso de soporte
	 *       vigente. Sin el, se deniega.</li>
	 *   <li><b>{@code RESTRINGIDO}</b> —la auditoria clinica— exige acceso de soporte <b>y</b>
	 *       un permiso clinico explicito. Ese permiso no existe hasta F4, asi que en Fase 1
	 *       <b>siempre deniega</b>. Que denegue esta probado: documenta que falta por diseño y
	 *       no por descuido.</li>
	 * </ul>
	 */
	private PermissionDecision evaluarComoPlataforma(
			PermissionQuery query, PermissionCode permiso, Instant at) {

		Optional<PermissionScope> base =
				RolePermissions.baseScope(RoleCode.PLATFORM_ADMIN, permiso);
		if (base.isEmpty()) {
			return PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
		}

		Long organizationId = query.organizationId();
		boolean conSoporte = organizationId != null
				&& hasSupportAccess(query.accountId(), organizationId, at);

		return switch (base.get()) {
			case GLOBAL -> PermissionDecision.concedida(
					PermissionScope.GLOBAL.name(), conSoporte);
			case SOPORTE -> conSoporte
					? PermissionDecision.concedida(PermissionScope.SOPORTE.name(), true)
					: PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
			// Restringido: soporte MAS permiso clinico explicito. El segundo no existe en F1.
			case RESTRINGIDO -> PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
			default -> PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
		};
	}

	/**
	 * Elige la membership con la que se decide, usando los criterios ya centralizados en
	 * {@link MembershipSelection}.
	 *
	 * <p>No se reescriben aca ni se sustituyen por "gana la mas privilegiada": ver el javadoc de
	 * esa clase, donde queda registrado por que la aparicion de {@link RolePermissions} en esta
	 * etapa no cambio el criterio.
	 */
	private Optional<Membership> elegirGobernante(
			List<Membership> memberships, Long consultorioId, Instant at) {

		if (consultorioId != null) {
			return MembershipSelection.applicableAt(memberships, consultorioId, at);
		}
		return MembershipSelection.organizationScoped(memberships).filter(m -> m.isValidAt(at));
	}

	private Optional<Membership> membershipGobernante(
			long accountId, long organizationId, Long consultorioId, Instant at) {

		List<Membership> memberships = membershipRepository
				.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(organizationId, accountId);
		return elegirGobernante(memberships, consultorioId, at);
	}

	/**
	 * Verifica que el alcance con el que se tiene el permiso cubra el recurso pedido.
	 *
	 * <p>Sin consultorio en la consulta, la decision es sobre la organizacion ENTERA y solo la
	 * cubre un alcance de organizacion o global. Con consultorio, un alcance de sede lo cubre
	 * unicamente si es SU sede: aceptar otra seria la escalada de privilegio que aparece sola en
	 * cuanto existan memberships por sede.
	 */
	private boolean alcanceCubre(
			PermissionScope alcance, Membership membership, Long consultorioIdPedido) {

		if (alcance.cubreLaOrganizacion()) {
			return true;
		}
		if (alcance != PermissionScope.CONSULTORIO && alcance != PermissionScope.ACTIVIDAD_PROPIA) {
			// OWN y CATALOGO no habilitan nada en Fase 1: sus acciones son de F3 en adelante.
			return false;
		}
		// ACTIVIDAD_PROPIA (G-1, DP-15) cubre la sede igual que CONSULTORIO: la diferencia no es
		// QUE sede alcanza sino QUE filas dentro de ella, y eso lo recorta quien lee el dato a
		// partir del `grantedByScope` de la decision.
		if (consultorioIdPedido == null) {
			return false;
		}
		return membership.covers(consultorioIdPedido);
	}

	/**
	 * Comprueba que la cuenta sobre la que se opera pertenezca a la organizacion del actor.
	 *
	 * <p>Es la mitad "sobre quien" de ADR-0019 y no se relaja: la matriz decide que puede hacer
	 * el actor, la membership del objetivo decide sobre quien. Una cuenta ajena se responde como
	 * inexistente.
	 */
	private boolean objetivoAlcanzable(PermissionQuery query, Instant at) {
		Long objetivo = query.targetAccountId();
		if (objetivo == null || objetivo == query.accountId()) {
			return true;
		}
		return membershipRepository
				.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
						query.organizationId(), objetivo)
				.stream()
				.anyMatch(m -> m.isValidAt(at));
	}

	private List<MembershipGrant> grantsVigentes(Membership membership, Instant at) {
		return grantRepository.findAllByMembershipIdAndActiveTrue(membership.getId()).stream()
				.filter(g -> g.isValidAt(at))
				.toList();
	}
}
