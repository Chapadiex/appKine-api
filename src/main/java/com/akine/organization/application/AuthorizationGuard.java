package com.akine.organization.application;

import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Punto unico de autorizacion de la capa {@code api} del modulo.
 *
 * <h2>Que cambio en AKINE-01.03, y que deliberadamente no cambio</h2>
 *
 * <p>Esta clase se llamaba {@code ProvisionalAuthorizationGuard} y su javadoc decia: <i>"toda la
 * autorizacion del modulo pasa por esta clase justamente para que ese reemplazo sea un solo
 * cambio en un solo archivo"</i>. Eso se cumplio: los ocho puntos de autorizacion del modulo
 * siguen siendo ocho llamadas a estos tres metodos desde dos controllers, y <b>ninguna de esas
 * ocho lineas se toco</b>. Lo que cambio es lo de adentro: el guard ya no consulta memberships
 * ni compara roles a mano, delega en {@link PermissionGuard}.
 *
 * <p>El nombre perdio el "Provisional" porque ya no lo es. Las firmas no cambiaron: siguen
 * recibiendo primitivos porque {@code application} no puede conocer HTTP ni la forma del token.
 *
 * <h2>Las dos reglas de codigos que esta clase preserva</h2>
 *
 * <ul>
 *   <li><b>Sin contexto validado &rarr; 403</b>, jamas 401. El interceptor del frontend borra el
 *       token ante cualquier 401, asi que un 401 desde un endpoint de negocio deja al usuario en
 *       un bucle de login del que no sale.</li>
 *   <li><b>Contexto de otra organizacion &rarr; 404</b>, jamas 403. Un 403 confirmaria que esa
 *       organizacion existe y alcanzaria con probar ids consecutivos para enumerar los clientes
 *       del SaaS.</li>
 * </ul>
 *
 * <h2>Por que el contexto se sigue comparando</h2>
 *
 * <p>Un actor puede administrar la organizacion A y estar operando con un token acotado a la
 * organizacion B. Sin la comparacion, un id de A en la URL le daria acceso administrativo
 * mientras trabaja en B: exactamente la fuga cross-tenant que el modelo de contexto existe para
 * impedir (RN-M01-003). El evaluador decide permisos; esta comparacion decide que el recurso
 * pedido sea el del contexto del request.
 */
@Service
public class AuthorizationGuard {

	private static final Logger log = LoggerFactory.getLogger(AuthorizationGuard.class);

	private final PermissionGuard permissionGuard;
	private final AccountContextService accountContextService;
	private final TenantContextHolder tenantContextHolder;
	private final SupportAccessReadAuditor supportAccessReadAuditor;

	public AuthorizationGuard(
			PermissionGuard permissionGuard,
			AccountContextService accountContextService,
			TenantContextHolder tenantContextHolder,
			SupportAccessReadAuditor supportAccessReadAuditor) {
		this.permissionGuard = permissionGuard;
		this.accountContextService = accountContextService;
		this.tenantContextHolder = tenantContextHolder;
		this.supportAccessReadAuditor = supportAccessReadAuditor;
	}

	/**
	 * Exige el rol de plataforma.
	 *
	 * <p><b>El booleano ya no sale de un claim del token.</b> Hasta 01.02 lo llenaba
	 * {@code AuthenticatedJwtPrincipal} comparando el claim {@code rol} contra
	 * {@code "PLATFORM_ADMIN"} —un claim que nunca valia eso, con lo que las tres operaciones de
	 * plataforma publicadas en el contrato no las podia ejecutar nadie—. Desde 01.03 lo resuelve
	 * {@code PlatformRoleDirectory} contra {@code platform_role}, una vez por request y sin
	 * cache. La firma no cambio; el origen del dato si.
	 *
	 * <p>Sigue siendo un flag y no una membership: un {@code PLATFORM_ADMIN} no tiene membership
	 * en ninguna organizacion (matriz §1.3), asi que buscarle una seria contradictorio.
	 *
	 * @throws AccessDeniedException si el principal no lo tiene (403). Las rutas de plataforma
	 *         son publicas en el contrato: su existencia no es secreta, asi que aca 403 no
	 *         filtra nada
	 */
	public void requirePlatformAdmin(boolean platformAdmin) {
		if (!platformAdmin) {
			throw new AccessDeniedException("Operacion reservada a la administracion de plataforma");
		}
	}

	/**
	 * Exige administrar la organizacion pedida.
	 *
	 * <p>Se traduce a {@link PermissionCode#TENANT_READ} con alcance de organizacion, que en la
	 * matriz §6 solo tiene {@code ORG_ADMIN} (y {@code PLATFORM_ADMIN}, global). El evaluador
	 * exige ademas que la membership sea <b>de alcance organizacion</b>: una acotada a una sede
	 * no puede autorizar sobre el tenant entero, y aceptarlo seria una escalada de privilegio
	 * que aparece sola en cuanto existan memberships por sede.
	 *
	 * <p><b>Contradiccion conocida del catalogo, dicha y no tapada.</b> Uno de los tres usos de
	 * este metodo es la edicion de la propia organizacion, y el catalogo de la matriz §5 no
	 * tiene un codigo para "editar la propia organizacion": §6 le da a {@code ORG_ADMIN}
	 * unicamente {@code tenant:read}, mientras que §4 dice explicitamente que su "Limitado"
	 * <b>incluye</b> editar los datos de su organizacion (y excluye cambiar de plan y
	 * suspender, que son de plataforma). Los dos parrafos de la matriz no coinciden. Se resolvio
	 * por el lado que preserva el comportamiento vigente y el texto de §4 —{@code tenant:read}
	 * cubre los tres usos— en vez de inventar un codigo que la matriz, que es vinculante, no
	 * declara. Queda registrado para la etapa que enmiende el catalogo.
	 *
	 * <p><b>El {@code PLATFORM_ADMIN} ya no pasa de largo.</b> Hasta esta correccion habia un
	 * {@code if (platformAdmin) return;} antes de cualquier evaluacion: leia el perfil y la
	 * suscripcion de cualquier tenant sin {@code support_access} vigente y sin dejar una sola
	 * fila de auditoria, con lo que el acceso de soporte —autoconcedido, con motivo, cuatro
	 * horas y control posterior— era opcional justo donde mas se nota. Ahora pasa por el
	 * evaluador como todos, y {@code tenant:read} es de alcance {@code SOPORTE} para su rol
	 * (ver {@code RolePermissions}): sin soporte vigente es 403, y con soporte queda
	 * {@code SUPPORT_ACCESS_USED} en la base.
	 *
	 * @param contextOrganizationId organizacion del contexto ya validado del request, o
	 *                              {@code null} si el request no trae contexto. Un
	 *                              {@code PLATFORM_ADMIN} no tiene contexto y no se le exige
	 * @throws AccessDeniedException         si no administra esa organizacion, o si el request
	 *                                       no trae contexto validado (403)
	 * @throws OrganizationNotFoundException si la organizacion pedida es de otro tenant (404)
	 */
	@Transactional(readOnly = true)
	public void requireOrgAdmin(
			long accountId, long organizationId, Long contextOrganizationId, boolean platformAdmin) {

		// El administrador de plataforma NO se saltea la evaluacion: se saltea la comparacion
		// de contexto, que es otra cosa. No tiene membership en ningun tenant (matriz §1.3) y
		// por lo tanto tampoco contexto de trabajo; exigirselo lo dejaria con 403 en todos
		// lados. Lo que decide si pasa o no es el evaluador, igual que para cualquier otro.
		if (!platformAdmin) {
			requireSameContext(accountId, organizationId, contextOrganizationId);
		}
		exigirTenantRead(accountId, organizationId);
	}

	/**
	 * Exige ser miembro vigente de la organizacion pedida, con cualquier rol.
	 *
	 * <p><b>Esto es una ENMIENDA declarada a la matriz, no un descuido.</b> La matriz §6 le da
	 * {@code tenant:read} solo a {@code ORG_ADMIN}; este metodo autoriza la lectura del perfil de
	 * la organizacion y del listado de consultorios a <b>cualquier miembro vigente</b>. Los dos
	 * motivos: es el comportamiento vigente desde 01.01 y ajustarlo seria un cambio incompatible
	 * en un endpoint ya publicado; y el selector de contexto necesita el nombre de la
	 * organizacion y sus sedes para los roles que no administran nada — sin eso, un
	 * {@code PROFESIONAL} no puede ni elegir donde trabaja. La enmienda esta escrita en
	 * {@code docs/seguridad/matriz-permisos-minima.md}; aplicarla en silencio no seria legitimo,
	 * porque la matriz es vinculante.
	 *
	 * <p>Lo que la enmienda <b>no</b> habilita: los datos de la suscripcion, que siguen detras de
	 * {@link #requireOrgAdmin} y por lo tanto de {@code tenant:read}.
	 *
	 * <p><b>Rechaza con 404 y no con 403.</b> Responder "prohibido" confirmaria que esa
	 * organizacion existe. No pertenecer y no existir se responden igual.
	 *
	 * <p><b>El {@code PLATFORM_ADMIN} es el unico que recibe 403 y no 404, y no filtra nada:</b>
	 * sin {@code support_access} vigente se le rechaza igual exista o no la organizacion —el
	 * evaluador ni siquiera la busca—, asi que la respuesta es uniforme y no sirve para
	 * enumerar tenants. Y de todas formas ya puede enumerarlos por el endpoint de plataforma:
	 * lo que hay que impedirle no es saber que existen, es leerlos sin dejar rastro.
	 *
	 * @throws OrganizationNotFoundException si no es miembro vigente (404)
	 */
	@Transactional(readOnly = true)
	public void requireMember(
			long accountId, long organizationId, Long contextOrganizationId, boolean platformAdmin) {

		if (platformAdmin) {
			// Un administrador de plataforma no tiene membership que comprobar, asi que el
			// unico control posible es el del evaluador. Antes ni siquiera eso: entraba por el
			// return de arriba y leia el perfil y las sedes de cualquier tenant sin
			// support_access y sin dejar una sola fila.
			exigirTenantRead(accountId, organizationId);
			return;
		}
		if (contextOrganizationId == null || contextOrganizationId != organizationId
				|| !accountContextService.hasActiveMembership(accountId, organizationId)) {
			log.info("Acceso a organizacion ajena rechazado: accountId={} organizationId={}",
					accountId, organizationId);
			throw new OrganizationNotFoundException(organizationId);
		}
	}

	/**
	 * El actor de una operacion sobre {@code /organizations/{organizationId}/...}, con la
	 * organizacion de la ruta ya comparada contra la del contexto.
	 *
	 * <p><b>El evaluador de permisos no alcanza para aislar, y por eso existe esto.</b>
	 * {@link PermissionGuard} decide si la cuenta tiene el permiso en la organizacion que se le
	 * pregunta, y se le pregunta la de la ruta. Una cuenta que administra dos organizaciones lo
	 * tiene en las dos: con el contexto de A elegido, pedir {@code /organizations/B/...} pasaba
	 * el evaluador y devolvia los datos de B. El contexto activo es el limite del request —
	 * cambiarlo es un acto explicito y auditado—, asi que la ruta tiene que coincidir con el
	 * antes de preguntar nada: distinta es 404, igual que una organizacion inexistente; sin
	 * contexto es 403.
	 *
	 * <p>El administrador de plataforma se saltea la comparacion por el mismo motivo que en
	 * {@link #requireOrgAdmin}: no tiene membership ni contexto, y lo que decide si pasa es el
	 * evaluador, que exige un acceso de soporte vigente.
	 */
	public OperatingActor actorSobre(
			long accountId, boolean platformAdmin, Long contextOrganizationId, long organizationId) {

		if (!platformAdmin) {
			requireSameContext(accountId, organizationId, contextOrganizationId);
		}
		return new OperatingActor(accountId, platformAdmin, consultorioDelContexto());
	}

	/**
	 * Sede del contexto validado del request, o {@code null} si no hay contexto.
	 *
	 * <p>Lo necesitan las decisiones de alcance {@code CONSULTORIO}: sin la sede, el evaluador
	 * solo puede decidir sobre la organizacion entera y un {@code CONSULTORIO_ADMIN} quedaria
	 * sin ningun permiso. Sale del contexto ya revalidado contra la base, <b>nunca</b> de un
	 * parametro del cliente.
	 *
	 * <p>{@code TenantContextHolder} es una interfaz de {@code platform.spi} y no expone nada de
	 * HTTP, asi que consumirla desde {@code application} no viola {@code application_no_conoce_http}.
	 */
	public Long consultorioDelContexto() {
		return tenantContextHolder.current()
				.map(RequestTenantContext::consultorioId)
				.orElse(null);
	}

	/**
	 * Exige que la organizacion pedida sea la del contexto ya validado del request.
	 *
	 * <p><b>Son dos rechazos distintos y no se responden igual.</b> Sin contexto activo el actor
	 * todavia no eligio donde trabaja: es un 403 que el frontend traduce en "elegi un
	 * consultorio", y por eso nunca puede ser un 401. Con un contexto que no es el de la
	 * organizacion pedida se esta preguntando por un tenant ajeno: eso es un 404.
	 */
	/**
	 * Exige {@code tenant:read} sobre la organizacion y deja el rastro si el acceso se apoyo en
	 * soporte.
	 *
	 * <h2>Por que esto es UN solo punto y no dos ni cuatro</h2>
	 *
	 * <p>{@code OrganizationService} y {@code SubscriptionService} <b>no evaluan permisos</b>:
	 * su unico punto de autorizacion son estos dos metodos. Duplicarles el registro adentro
	 * habria dado dos copias que se olvidan por separado —exactamente como se olvidaron las
	 * cuatro que esta tarea vino a cerrar—. {@code AuditQueryService} si evalua el suyo, con su
	 * propio codigo y su propio alcance, asi que lo registra el; forzar los dos casos en una
	 * abstraccion comun obligaria a pasarle el permiso y el alcance por parametro y no ahorraria
	 * ni una linea.
	 *
	 * <p><b>El registro va por {@link SupportAccessReadAuditor} y no por el {@code AuditTrail}
	 * directo</b>: estos metodos son {@code readOnly = true}, y en una transaccion de solo
	 * lectura Hibernate deja el flush en MANUAL — la fila no llegaria nunca a la base. El
	 * razonamiento completo, con las alternativas descartadas, esta en el javadoc de esa clase.
	 */
	private void exigirTenantRead(long accountId, long organizationId) {
		Instant ahora = Instant.now();
		PermissionDecision decision = permissionGuard.requirePermission(PermissionQuery.of(
				accountId, PermissionCode.TENANT_READ.code(), organizationId, ahora));

		if (decision.viaSupportAccess()) {
			supportAccessReadAuditor.record(AuditEvents.usoDeSoporte(
					organizationId, null, accountId,
					PermissionCode.TENANT_READ.code(), organizationId, ahora));
		}
	}

	private void requireSameContext(
			long accountId, long organizationId, Long contextOrganizationId) {
		if (contextOrganizationId == null) {
			log.info("Request sin contexto validado sobre una organizacion: "
					+ "accountId={} organizationId={}", accountId, organizationId);
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (contextOrganizationId != organizationId) {
			log.info("Contexto del request distinto de la organizacion pedida: "
					+ "accountId={} organizationId={}", accountId, organizationId);
			throw new OrganizationNotFoundException(organizationId);
		}
	}
}
