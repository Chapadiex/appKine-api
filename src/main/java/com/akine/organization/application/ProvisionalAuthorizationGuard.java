package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Autorizacion GRUESA y provisional del modulo.
 *
 * <p>TODO(AKINE-01.03): reemplazar por el evaluador de la matriz de permisos. Toda la
 * autorizacion del modulo pasa por esta clase justamente para que ese reemplazo sea un solo
 * cambio en un solo archivo. Si la logica se dispersa por los controllers, 01.03 tiene que
 * cazarla endpoint por endpoint y garantizado se le escapa uno.
 *
 * <p>Lo que hace hoy, y nada mas:
 * <ul>
 *   <li>{@code PLATFORM_ADMIN}: un flag del principal autenticado, que provee {@code identity}
 *       en 01.02. No se consulta ninguna tabla.</li>
 *   <li>{@code ORG_ADMIN}: membership vigente con {@code role_code = ORG_ADMIN} en la
 *       organizacion pedida, que ademas tiene que coincidir con la del contexto ya validado
 *       del request.</li>
 * </ul>
 *
 * <p><b>Por que recibe primitivos y no un principal.</b> {@code application} no puede conocer
 * HTTP ni la representacion del token, que ademas todavia no existe: 01.02 la define. La capa
 * {@code api} extrae {@code accountId}, el flag de plataforma y el contexto validado, y los
 * pasa como parametros. Cuando el principal exista, este guard no cambia.
 *
 * <p><b>Por que el contexto se compara siempre.</b> Un actor puede ser {@code ORG_ADMIN} en la
 * organizacion A y estar operando con un token acotado a la organizacion B. Sin la
 * comparacion, un id de A en la URL le daria acceso administrativo mientras trabaja en B, que
 * es exactamente la fuga cross-tenant que el modelo de contexto existe para impedir
 * (RN-M01-003).
 */
@Service
public class ProvisionalAuthorizationGuard {

	private static final Logger log = LoggerFactory.getLogger(ProvisionalAuthorizationGuard.class);

	private final MembershipRepositoryPort membershipRepository;
	private final AccountContextService accountContextService;

	public ProvisionalAuthorizationGuard(
			MembershipRepositoryPort membershipRepository,
			AccountContextService accountContextService) {
		this.membershipRepository = membershipRepository;
		this.accountContextService = accountContextService;
	}

	/**
	 * Exige el rol de plataforma.
	 *
	 * <p>Es un flag y no una membership: {@code PLATFORM_ADMIN} esta por encima de cualquier
	 * tenant, asi que buscarle una membership en una organizacion seria contradictorio.
	 *
	 * @throws AccessDeniedException si el principal no lo tiene (403)
	 */
	public void requirePlatformAdmin(boolean platformAdmin) {
		if (!platformAdmin) {
			throw new AccessDeniedException("Operacion reservada a la administracion de plataforma");
		}
	}

	/**
	 * Exige administrar la organizacion pedida.
	 *
	 * <p>{@code PLATFORM_ADMIN} pasa sin membership: administra la plataforma entera. Para
	 * cualquier otro, las tres condiciones son necesarias — organizacion del contexto ==
	 * organizacion pedida, membership vigente, rol {@code ORG_ADMIN}.
	 *
	 * @param contextOrganizationId organizacion del contexto ya validado del request, o
	 *                              {@code null} si el request no trae contexto
	 * @throws AccessDeniedException si no administra esa organizacion (403)
	 */
	@Transactional(readOnly = true)
	public void requireOrgAdmin(
			long accountId, long organizationId, Long contextOrganizationId, boolean platformAdmin) {

		if (platformAdmin) {
			return;
		}
		requireSameContext(accountId, organizationId, contextOrganizationId);

		Optional<Membership> membership = membershipRepository
				.findByOrganizationIdAndAccountIdAndActiveTrue(organizationId, accountId)
				.filter(m -> m.isValidAt(Instant.now()))
				.filter(m -> m.getRoleCode() == RoleCode.ORG_ADMIN);

		if (membership.isEmpty()) {
			log.info("Acceso administrativo rechazado: accountId={} organizationId={}",
					accountId, organizationId);
			throw new AccessDeniedException("Se requiere administrar la organizacion");
		}
	}

	/**
	 * Exige ser miembro vigente de la organizacion pedida, con cualquier rol.
	 *
	 * <p><b>Rechaza con 404 y no con 403.</b> Responder "prohibido" confirmaria que esa
	 * organizacion existe, y alcanzaria con probar ids para enumerar los clientes del SaaS. No
	 * pertenecer y no existir se responden igual.
	 *
	 * @throws OrganizationNotFoundException si no es miembro vigente (404)
	 */
	@Transactional(readOnly = true)
	public void requireMember(
			long accountId, long organizationId, Long contextOrganizationId, boolean platformAdmin) {

		if (platformAdmin) {
			return;
		}
		if (contextOrganizationId == null || contextOrganizationId != organizationId
				|| !accountContextService.hasActiveMembership(accountId, organizationId)) {
			log.info("Acceso a organizacion ajena rechazado: accountId={} organizationId={}",
					accountId, organizationId);
			throw new OrganizationNotFoundException(organizationId);
		}
	}

	private void requireSameContext(
			long accountId, long organizationId, Long contextOrganizationId) {
		if (contextOrganizationId == null || contextOrganizationId != organizationId) {
			log.info("Contexto del request distinto de la organizacion pedida: "
					+ "accountId={} organizationId={}", accountId, organizationId);
			throw new AccessDeniedException("La organizacion pedida no es la del contexto activo");
		}
	}
}
