package com.akine.organization.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.organization.application.ConsultorioAltaEnCursoException;
import com.akine.organization.application.IdempotencyKeyConflictException;
import com.akine.organization.application.PlanNotFoundException;
import com.akine.organization.domain.exception.ConsultorioHasActiveReferencesException;
import com.akine.organization.domain.exception.ConsultorioInactiveException;
import com.akine.organization.domain.exception.ConsultorioNameTakenException;
import com.akine.organization.domain.exception.ConsultorioNotAccessibleException;
import com.akine.organization.domain.exception.ContextNotAuthorizedException;
import com.akine.organization.domain.exception.LastConsultorioException;
import com.akine.organization.domain.exception.FounderRevocationNotAllowedException;
import com.akine.organization.domain.exception.GrantAlreadyActiveException;
import com.akine.organization.domain.exception.LastAdminException;
import com.akine.organization.domain.exception.MembershipAlreadyExistsException;
import com.akine.organization.domain.exception.MembershipNotAccessibleException;
import com.akine.organization.domain.exception.MembershipNotActiveException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.domain.exception.PlatformRoleNotFoundException;
import com.akine.organization.domain.exception.SelfRevokeNotAllowedException;
import com.akine.organization.domain.exception.SupportAccessNotFoundException;
import com.akine.organization.domain.exception.UnknownPermissionCodeException;
import com.akine.organization.domain.exception.FeatureNotAvailableException;
import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.OrganizationSlugTakenException;
import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Traduccion de las excepciones de dominio de {@code organization} a Problem Details.
 *
 * <h2>Por que no esta en {@code GlobalExceptionHandler}</h2>
 * <b>No es duplicacion: es la unica ubicacion legal.</b> {@code GlobalExceptionHandler} vive en
 * {@code platform}, que es el modulo base. Mapear ahi estas excepciones exige importarlas, y eso
 * crea una dependencia {@code platform -> organization} que cierra un ciclo —{@code organization}
 * ya depende de {@code platform.spi} para la auditoria y el contexto de tenant—. Verificado
 * contra {@code ModuleArchitectureTest}: rompe {@code modulos_solo_se_alcanzan_por_su_spi} y
 * {@code sin_ciclos_entre_modulos}.
 * <p>Lo que se comparte con {@code platform} es lo que importa: la <b>convencion</b>. Mismo
 * formato RFC 7807, mismos {@code type} bajo {@code https://akine.app/problems/}, misma regla de
 * no filtrar nada interno. Cada modulo funcional que agregue excepciones propias hace lo mismo
 * en su propio {@code api}.
 *
 * <h2>Orden</h2>
 * {@code HIGHEST_PRECEDENCE} es obligatorio, no cosmetico. {@code GlobalExceptionHandler} tiene
 * un manejador de {@code Exception} como red de contencion, y Spring resuelve por advice antes
 * que por especificidad: si el advice global se consultara primero, se tragaria estas
 * excepciones y las devolveria como 500. Con este orden, lo especifico gana y la red de
 * contencion sigue siendo lo ultimo.
 *
 * <h2>Que se publica y que no</h2>
 * Ninguna respuesta lleva nombres de clase, paquetes, SQL ni stack traces. Los errores de limite
 * de plan SI llevan el limite y el uso actual: es lo que el cliente necesita para decirle al
 * usuario que le falta y ofrecerle un upgrade con sentido, y no revela nada de otros tenants ni
 * de la implementacion.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrganizationProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(OrganizationProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI INVALID_SUBSCRIPTION_TRANSITION =
			ProblemType.INVALID_SUBSCRIPTION_TRANSITION.uri();
	private static final URI PLAN_LIMIT_EXCEEDED =
			ProblemType.PLAN_LIMIT_EXCEEDED.uri();
	private static final URI FEATURE_NOT_AVAILABLE =
			ProblemType.FEATURE_NOT_AVAILABLE.uri();
	private static final URI SUBSCRIPTION_SUSPENDED =
			ProblemType.SUBSCRIPTION_SUSPENDED.uri();
	private static final URI ORGANIZATION_SLUG_TAKEN =
			ProblemType.ORGANIZATION_SLUG_TAKEN.uri();
	private static final URI IDEMPOTENCY_KEY_CONFLICT =
			ProblemType.IDEMPOTENCY_KEY_CONFLICT.uri();
	private static final URI FORBIDDEN = ProblemType.FORBIDDEN.uri();
	private static final URI LAST_ADMIN_REQUIRED =
			ProblemType.LAST_ADMIN_REQUIRED.uri();
	private static final URI SELF_REVOKE_NOT_ALLOWED =
			ProblemType.SELF_REVOKE_NOT_ALLOWED.uri();
	private static final URI MEMBERSHIP_NOT_ACTIVE =
			ProblemType.MEMBERSHIP_NOT_ACTIVE.uri();
	private static final URI MEMBERSHIP_ALREADY_EXISTS =
			ProblemType.MEMBERSHIP_ALREADY_EXISTS.uri();
	private static final URI GRANT_ALREADY_ACTIVE =
			ProblemType.GRANT_ALREADY_ACTIVE.uri();
	private static final URI CONCURRENT_MODIFICATION =
			ProblemType.CONCURRENT_MODIFICATION.uri();
	private static final URI VALIDATION_ERROR =
			ProblemType.VALIDATION_ERROR.uri();
	private static final URI CONFLICT = ProblemType.CONFLICT.uri();
	private static final URI CONSULTORIO_NAME_TAKEN =
			ProblemType.CONSULTORIO_NAME_TAKEN.uri();
	private static final URI CONSULTORIO_INACTIVE =
			ProblemType.CONSULTORIO_INACTIVE.uri();
	private static final URI CONSULTORIO_ALREADY_INACTIVE =
			ProblemType.CONSULTORIO_ALREADY_INACTIVE.uri();
	private static final URI LAST_CONSULTORIO_REQUIRED =
			ProblemType.LAST_CONSULTORIO_REQUIRED.uri();
	private static final URI CONSULTORIO_HAS_ACTIVE_REFERENCES =
			ProblemType.CONSULTORIO_HAS_ACTIVE_REFERENCES.uri();
	private static final URI MISSING_TENANT_CONTEXT =
			ProblemType.MISSING_TENANT_CONTEXT.uri();

	/**
	 * Organizacion inexistente, dada de baja, o de otro tenant.
	 *
	 * <p><b>Los tres casos responden lo mismo, a proposito.</b> Distinguirlos permitiria
	 * enumerar los clientes del SaaS probando ids consecutivos: bastaria comparar 403 contra 404
	 * para saber cuales existen. Para quien no tiene acceso, el recurso no existe.
	 */
	@ExceptionHandler(OrganizationNotFoundException.class)
	public ProblemDetail handleOrganizationNotFound(OrganizationNotFoundException exception) {
		log.debug("Organizacion no accesible: organizationId={}", exception.getOrganizationId());
		return noEncontrado();
	}

	/**
	 * Contexto de trabajo no autorizado para la cuenta.
	 *
	 * <p>Tambien 404 y no 403, por el mismo motivo: responder "prohibido" confirmaria que esa
	 * organizacion o ese consultorio existen. El id pedido va al log, nunca a la respuesta.
	 */
	@ExceptionHandler(ContextNotAuthorizedException.class)
	public ProblemDetail handleContextNotAuthorized(ContextNotAuthorizedException exception) {
		log.info("Contexto no autorizado: accountId={} organizationId={} consultorioId={}",
				exception.getAccountId(),
				exception.getOrganizationId(),
				exception.getConsultorioId());
		return noEncontrado();
	}

	/**
	 * Plan inexistente o ya no contratable.
	 *
	 * <p>Un plan retirado del catalogo se responde igual que uno que nunca existio: para quien
	 * intenta contratarlo, la diferencia no cambia nada de lo que puede hacer.
	 */
	@ExceptionHandler(PlanNotFoundException.class)
	public ProblemDetail handlePlanNotFound(PlanNotFoundException exception) {
		log.debug("Plan no contratable: planCode={}", exception.getPlanCode());
		return noEncontrado();
	}

	/**
	 * Salto que la maquina de estados de la suscripcion no admite.
	 *
	 * <p>Se publican los estados origen y destino porque el cliente los envio o los conoce: no
	 * es informacion nueva, y sin ellos el mensaje seria inaccionable. Repetir el estado actual
	 * cuenta como transicion invalida: no es un no-op silencioso.
	 */
	@ExceptionHandler(InvalidSubscriptionTransitionException.class)
	public ProblemDetail handleInvalidTransition(InvalidSubscriptionTransitionException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La suscripcion no admite pasar de " + exception.getFrom()
						+ " a " + exception.getTo() + ".");
		problem.setTitle("Transicion de suscripcion no permitida");
		problem.setType(INVALID_SUBSCRIPTION_TRANSITION);
		problem.setProperty("fromStatus", exception.getFrom());
		problem.setProperty("toStatus", exception.getTo());
		return problem;
	}

	/**
	 * Alta rechazada por un limite del plan contratado.
	 *
	 * <p>Se publican el limite y el uso actual deliberadamente: sin ellos el cliente no puede
	 * decirle al usuario que le falta ni ofrecerle el plan que lo resuelve. Son datos del propio
	 * tenant y no revelan nada de la implementacion.
	 */
	@ExceptionHandler(PlanLimitExceededException.class)
	public ProblemDetail handlePlanLimitExceeded(PlanLimitExceededException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Alcanzo el limite del plan contratado para " + exception.getLimitCode() + ".");
		problem.setTitle("Limite del plan alcanzado");
		problem.setType(PLAN_LIMIT_EXCEEDED);
		problem.setProperty("limitCode", exception.getLimitCode());
		problem.setProperty("limitValue", exception.getLimitValue());
		problem.setProperty("currentUsage", exception.getCurrentUsage());
		return problem;
	}

	/** Funcionalidad no incluida en el plan vigente. Se publica cual, para poder ofrecer upgrade. */
	@ExceptionHandler(FeatureNotAvailableException.class)
	public ProblemDetail handleFeatureNotAvailable(FeatureNotAvailableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La funcionalidad " + exception.getFeatureCode()
						+ " no esta incluida en el plan contratado.");
		problem.setTitle("Funcionalidad no incluida en el plan");
		problem.setType(FEATURE_NOT_AVAILABLE);
		problem.setProperty("featureCode", exception.getFeatureCode());
		return problem;
	}

	/**
	 * Mutacion de negocio bloqueada porque la suscripcion no esta activa.
	 *
	 * <p>409 y no 403: el actor tiene el permiso, lo que no esta habilitado es el estado del
	 * tenant. Y suspender bloquea, jamas destruye: las lecturas y la administracion de la
	 * suscripcion siguen disponibles —son las que hacen falta para salir de la suspension—.
	 */
	@ExceptionHandler(SubscriptionSuspendedException.class)
	public ProblemDetail handleSubscriptionSuspended(SubscriptionSuspendedException exception) {
		log.info("Mutacion bloqueada por suscripcion no activa: organizationId={}",
				exception.getOrganizationId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La suscripcion de la organizacion no esta activa: se permiten lecturas y "
						+ "administracion, no mutaciones de negocio.");
		problem.setTitle("Suscripcion suspendida");
		problem.setType(SUBSCRIPTION_SUSPENDED);
		return problem;
	}

	/**
	 * La misma clave de idempotencia se reuso con un contenido distinto.
	 *
	 * <p>Es un error del cliente y no un reintento: una clave identifica UN intento. Aceptarlo
	 * ejecutaria una operacion distinta bajo la promesa de que era la misma. La clave no se
	 * devuelve en el cuerpo —el cliente ya la tiene— y si se registra en el log, que es donde
	 * sirve para investigar.
	 */
	@ExceptionHandler(IdempotencyKeyConflictException.class)
	public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException exception) {
		log.warn("Clave de idempotencia reusada con contenido distinto: idempotencyKey={}",
				exception.getIdempotencyKey());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La clave de idempotencia ya fue usada para una solicitud con otro contenido. "
						+ "Use una clave nueva o reenvie la solicitud original.");
		problem.setTitle("Clave de idempotencia en conflicto");
		problem.setType(IDEMPOTENCY_KEY_CONFLICT);
		return problem;
	}

	/**
	 * El identificador legible pedido para la organizacion ya lo usa otro tenant.
	 *
	 * <p>409 y no 404: el slug es unico GLOBAL y el conflicto es con algo que el cliente
	 * acaba de elegir, no con un recurso que este intentando ver. Y no revela nada util para
	 * enumerar: el slug se usa en URLs publicas, asi que "esta tomado" es exactamente lo que
	 * cualquiera puede averiguar tipeandolo en el navegador.
	 *
	 * <p><b>El slug NO vuelve en el cuerpo.</b> Lo mando el cliente y reflejar en la respuesta
	 * lo que llega en el request es el vector clasico de XSS reflejado. Va al log.
	 */
	@ExceptionHandler(OrganizationSlugTakenException.class)
	public ProblemDetail handleSlugTaken(OrganizationSlugTakenException exception) {
		log.info("Slug de organizacion en conflicto: slug={}", exception.getSlug());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El identificador de la organizacion ya esta en uso. Elija otro.");
		problem.setTitle("Identificador de organizacion en uso");
		problem.setType(ORGANIZATION_SLUG_TAKEN);
		return problem;
	}

	// =================================================================================
	// AKINE-01.03 — permisos, memberships y grants
	// =================================================================================

	/**
	 * El actor tiene contexto valido y le falta el permiso sobre un recurso de SU organizacion.
	 *
	 * <p><b>403 y no 404</b>, al reves que casi todo el resto de este advice. El 404 existe para
	 * no confirmar la existencia de datos de otro tenant; sobre un recurso que el actor ya sabe
	 * que existe —es de su organizacion— no protege nada y ademas le miente. El permiso que falto
	 * SI se publica: es lo que el usuario necesita para saber que pedirle a su administrador.
	 */
	@ExceptionHandler(PermissionDeniedException.class)
	public ProblemDetail handlePermissionDenied(PermissionDeniedException exception) {
		log.info("Permiso denegado: accountId={} organizationId={} permiso={}",
				exception.getAccountId(), exception.getOrganizationId(),
				exception.getPermissionCode());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.FORBIDDEN,
				"No tiene el permiso necesario para realizar esta operacion.");
		problem.setTitle("Permiso insuficiente");
		problem.setType(FORBIDDEN);
		problem.setProperty("requiredPermission", exception.getPermissionCode());
		return problem;
	}

	/**
	 * Membership inexistente o fuera del alcance del actor.
	 *
	 * <p>404 en los dos casos: un 403 confirmaria que ese id existe y bastaria con recorrer
	 * numeros consecutivos para enumerar a los colaboradores de otros centros.
	 */
	@ExceptionHandler(MembershipNotAccessibleException.class)
	public ProblemDetail handleMembershipNotAccessible(MembershipNotAccessibleException exception) {
		log.debug("Membership no accesible: membershipId={}", exception.getMembershipId());
		return noEncontrado();
	}

	/**
	 * La operacion dejaria a la organizacion sin ningun administrador vigente.
	 *
	 * <p>409 y no 403: el actor tiene el permiso, lo que no admite la operacion es el estado en
	 * el que dejaria al tenant. La diferencia importa en la pantalla: "no podes" es inaccionable,
	 * "promove a otro administrador antes" no.
	 */
	@ExceptionHandler(LastAdminException.class)
	public ProblemDetail handleLastAdmin(LastAdminException exception) {
		log.info("Operacion rechazada por invariante de ultimo admin: organizationId={}",
				exception.getOrganizationId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La organizacion no puede quedar sin ningun administrador vigente. "
						+ "Asigne otro administrador antes de continuar.");
		problem.setTitle("Se requiere al menos un administrador");
		problem.setType(LAST_ADMIN_REQUIRED);
		return problem;
	}

	/** El actor intento quitarse a si mismo su ultimo rol administrativo. */
	@ExceptionHandler(SelfRevokeNotAllowedException.class)
	public ProblemDetail handleSelfRevoke(SelfRevokeNotAllowedException exception) {
		log.info("Self-revoke rechazado: accountId={}", exception.getAccountId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"No puede quitarse a si mismo su ultimo rol administrativo: despues de hacerlo "
						+ "no podria deshacerlo. Pidale a otro administrador que lo haga.");
		problem.setTitle("Operacion no permitida sobre uno mismo");
		problem.setType(SELF_REVOKE_NOT_ALLOWED);
		return problem;
	}

	/**
	 * Un administrador que no es el fundador intento desvincularlo.
	 *
	 * <p><b>403 y no 409</b>, a diferencia de los dos anteriores: no es una restriccion sobre el
	 * estado en el que quedaria el tenant, es una restriccion sobre QUIEN puede hacerlo.
	 */
	@ExceptionHandler(FounderRevocationNotAllowedException.class)
	public ProblemDetail handleFounderProtected(FounderRevocationNotAllowedException exception) {
		log.info("Intento de desvincular al fundador: membershipId={}", exception.getMembershipId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.FORBIDDEN,
				"El vinculo del fundador de la organizacion no puede ser modificado por otro "
						+ "administrador.");
		problem.setTitle("Vinculo protegido");
		problem.setType(FORBIDDEN);
		return problem;
	}

	/** La membership no esta en un estado desde el que la transicion pedida sea posible. */
	@ExceptionHandler(MembershipNotActiveException.class)
	public ProblemDetail handleMembershipNotActive(MembershipNotActiveException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El vinculo esta en " + exception.getEstadoActual()
						+ " y no admite pasar a " + exception.getEstadoPedido() + ".");
		problem.setTitle("Estado de vinculo incompatible");
		problem.setType(MEMBERSHIP_NOT_ACTIVE);
		problem.setProperty("currentState", exception.getEstadoActual().name());
		problem.setProperty("requestedState", exception.getEstadoPedido().name());
		return problem;
	}

	/**
	 * Ya hay un permiso adicional vigente igual sobre esa membership.
	 *
	 * <p>Viene de la clave duplicada, no de un chequeo previo: dos administradores otorgando el
	 * mismo permiso a la vez leerian los dos "no existe". El unique decide y el perdedor recibe
	 * <b>409</b>, no 500.
	 */
	@ExceptionHandler(GrantAlreadyActiveException.class)
	public ProblemDetail handleGrantAlreadyActive(GrantAlreadyActiveException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Ese permiso adicional ya esta vigente sobre este colaborador.");
		problem.setTitle("Permiso adicional ya otorgado");
		problem.setType(GRANT_ALREADY_ACTIVE);
		problem.setProperty("permissionCode", exception.getPermissionCode());
		return problem;
	}

	/**
	 * La cuenta ya tiene un vinculo con ese alcance en la organizacion.
	 *
	 * <p>El mensaje menciona explicitamente el vinculo revocado porque es el caso que mas
	 * desconcierta: el unique de {@code membership} incluye las filas historicas, asi que
	 * revincular a alguien en la sede donde ya trabajo choca. Es la decision D-13, abierta.
	 */
	@ExceptionHandler(MembershipAlreadyExistsException.class)
	public ProblemDetail handleMembershipExists(MembershipAlreadyExistsException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Esa cuenta ya tiene un vinculo con ese alcance en la organizacion. "
						+ "Puede tratarse de un vinculo anterior ya revocado.");
		problem.setTitle("Vinculo ya existente");
		problem.setType(MEMBERSHIP_ALREADY_EXISTS);
		return problem;
	}

	/** Codigo de permiso o de rol fuera del catalogo, o no otorgable en esta fase. */
	@ExceptionHandler(UnknownPermissionCodeException.class)
	public ProblemDetail handleUnknownPermission(UnknownPermissionCodeException exception) {
		log.info("Codigo fuera del catalogo: code={}", exception.getPermissionCode());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setTitle("Valor no valido");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	/** Acceso de soporte inexistente o ya cerrado. */
	@ExceptionHandler(SupportAccessNotFoundException.class)
	public ProblemDetail handleSupportAccessNotFound(SupportAccessNotFoundException exception) {
		log.debug("Acceso de soporte no encontrado: id={}", exception.getSupportAccessId());
		return noEncontrado();
	}

	/** Rol de plataforma inexistente o ya revocado. */
	@ExceptionHandler(PlatformRoleNotFoundException.class)
	public ProblemDetail handlePlatformRoleNotFound(PlatformRoleNotFoundException exception) {
		log.debug("Rol de plataforma no encontrado: id={}", exception.getPlatformRoleId());
		return noEncontrado();
	}

	/**
	 * El endpoint exige contexto de trabajo y el request no lo trae.
	 *
	 * <p><b>403 y jamas 401.</b> Mismo {@code type} que emite {@code TenantContextFilter} para el
	 * mismo caso, porque el frontend ramifica por ese campo y llevar al selector de contexto o
	 * mostrar un mensaje de permiso son dos reacciones distintas.
	 */
	@ExceptionHandler(MissingTenantContextException.class)
	public ProblemDetail handleMissingTenantContext(MissingTenantContextException exception) {
		log.info("Operacion sin contexto de trabajo sobre una ruta que lo exige");

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.FORBIDDEN,
				"Esta autenticado pero no selecciono un contexto de trabajo. "
						+ "Elija organizacion y consultorio para continuar.");
		problem.setTitle("Contexto de trabajo no seleccionado");
		problem.setType(MISSING_TENANT_CONTEXT);
		return problem;
	}

	/**
	 * La cuenta ya tenia el rol de plataforma vigente.
	 *
	 * <p>409 y no 500: viene de la clave unica, igual que el grant duplicado, y significa que
	 * otro administrador llego primero. Sin este manejador el caso caeria en la red de
	 * contencion del advice global.
	 */
	@ExceptionHandler(PlatformRoleAlreadyGrantedException.class)
	public ProblemDetail handlePlatformRoleAlreadyGranted(
			PlatformRoleAlreadyGrantedException exception) {

		log.info("Otorgamiento de rol de plataforma rechazado: ya estaba vigente");

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, "Esa cuenta ya tiene el rol de plataforma vigente.");
		problem.setTitle("Rol de plataforma ya otorgado");
		problem.setType(CONFLICT);
		return problem;
	}

	/**
	 * Dos escritores tocaron la misma fila y el {@code @Version} decidio.
	 *
	 * <p>409 y no 500: el perdedor no hizo nada mal, llego segundo. El cliente reintenta sobre el
	 * estado nuevo, que es exactamente lo que el optimistic lock existe para forzar.
	 */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException exception) {
		log.info("Modificacion concurrente detectada por optimistic locking");

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Otro usuario modifico este recurso mientras usted trabajaba. "
						+ "Vuelva a cargarlo e intente de nuevo.");
		problem.setTitle("Modificacion concurrente");
		problem.setType(CONCURRENT_MODIFICATION);
		return problem;
	}

	// =================================================================================
	// AKINE-02.01 — sedes
	// =================================================================================

	/**
	 * Sede inexistente o de otro tenant.
	 *
	 * <p>404 en los dos casos: un 403 confirmaria que ese id existe y bastaria recorrer numeros
	 * consecutivos para enumerar las sedes del SaaS.
	 *
	 * <p><b>Una sede INACTIVA no llega aca.</b> Se lee con 200 y conserva su historia: devolver
	 * 404 sobre ella seria borrar informacion historica por la puerta de atras (RF-M03-004).
	 */
	@ExceptionHandler(ConsultorioNotAccessibleException.class)
	public ProblemDetail handleConsultorioNotAccessible(ConsultorioNotAccessibleException exception) {
		log.debug("Sede no accesible: consultorioId={}", exception.getConsultorioId());
		return noEncontrado();
	}

	/**
	 * Ya hay una sede vigente con ese nombre en el tenant.
	 *
	 * <p>Viene de la clave duplicada, no de un chequeo previo: dos altas simultaneas con el mismo
	 * nombre leerian las dos "no existe". El unique decide y el perdedor recibe <b>409</b>, no
	 * 500.
	 *
	 * <p><b>El nombre NO vuelve en el cuerpo.</b> Lo mando el cliente, y reflejar en la respuesta
	 * lo que llega en el request es el vector clasico de XSS reflejado. Va al log.
	 *
	 * <p>El mensaje menciona explicitamente la reutilizacion porque es el caso que desconcierta:
	 * el nombre de una sede DADA DE BAJA si se puede reusar, asi que un 409 aca significa que hay
	 * otra sede vigente, no una historica.
	 */
	@ExceptionHandler(ConsultorioNameTakenException.class)
	public ProblemDetail handleConsultorioNameTaken(ConsultorioNameTakenException exception) {
		log.info("Nombre de sede en conflicto: name={}", exception.getName());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Ya existe una sede vigente con ese nombre en la organizacion. El nombre de una "
						+ "sede dada de baja si se puede reusar.");
		problem.setTitle("Nombre de sede en uso");
		problem.setType(CONSULTORIO_NAME_TAKEN);
		return problem;
	}

	/**
	 * La sede esta dada de baja y no admite la operacion pedida (RN-M03-003).
	 *
	 * <p>409 y no 403 —el actor tiene el permiso, lo que no admite la operacion es el estado— y
	 * 409 y no 404 —la sede existe, es del tenant del actor y la ve en su propio listado—.
	 *
	 * <p>El {@code type} distingue editar de volver a dar de baja porque el frontend ramifica por
	 * ahi y los dos mensajes son distintos.
	 */
	@ExceptionHandler(ConsultorioInactiveException.class)
	public ProblemDetail handleConsultorioInactive(ConsultorioInactiveException exception) {
		boolean segundaBaja =
				exception.getOperacion() == ConsultorioInactiveException.Operacion.BAJA;

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				segundaBaja
						? "La sede ya estaba dada de baja."
						: "La sede esta dada de baja y no admite modificaciones. Su informacion "
								+ "sigue siendo consultable.");
		problem.setTitle(segundaBaja ? "Sede ya dada de baja" : "Sede dada de baja");
		problem.setType(segundaBaja ? CONSULTORIO_ALREADY_INACTIVE : CONSULTORIO_INACTIVE);
		return problem;
	}

	/**
	 * La baja dejaria al tenant sin ninguna sede activa.
	 *
	 * <p>409 y no 403: el actor tiene el permiso; lo que no admite la operacion es el estado en
	 * el que dejaria al tenant. La diferencia importa en la pantalla: "no podes" es
	 * inaccionable, "crea otra sede antes de cerrar esta" no.
	 *
	 * <p>Es una restriccion AGREGADA al comportamiento de RF-M03-004: ningun requerimiento la
	 * pide, y esta declarada como decision en el registro de cierre de la etapa.
	 */
	@ExceptionHandler(LastConsultorioException.class)
	public ProblemDetail handleLastConsultorio(LastConsultorioException exception) {
		log.info("Baja rechazada por invariante de ultima sede: organizationId={}",
				exception.getOrganizationId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La organizacion no puede quedar sin ninguna sede activa: sin una sede nadie "
						+ "podria elegir contexto de trabajo. Cree otra sede antes de dar de baja "
						+ "esta.");
		problem.setTitle("Se requiere al menos una sede activa");
		problem.setType(LAST_CONSULTORIO_REQUIRED);
		return problem;
	}

	/**
	 * Algun modulo declaro referencias vigentes que bloquean la baja de la sede.
	 *
	 * <p>Lo emite {@code scheduling.infrastructure.SedeConTurnosPendientes} cuando la sede tiene
	 * turnos pendientes (paquete E-1). El codigo estaba reservado desde 02.01 y nadie lo emitia
	 * hasta entonces.
	 *
	 * <p>Se publican el tipo y la cantidad de referencias: son datos del propio tenant, no
	 * revelan nada de otros, y sin ellos el mensaje seria inaccionable.
	 */
	@ExceptionHandler(ConsultorioHasActiveReferencesException.class)
	public ProblemDetail handleConsultorioHasReferences(
			ConsultorioHasActiveReferencesException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La sede tiene registros vigentes que impiden darla de baja. Resuelvalos antes de "
						+ "continuar.");
		problem.setTitle("La sede tiene referencias vigentes");
		problem.setType(CONSULTORIO_HAS_ACTIVE_REFERENCES);
		problem.setProperty("referenceType", exception.getReferenceType());
		problem.setProperty("referenceCount", exception.getReferenceCount());
		return problem;
	}

	/**
	 * Dos altas de sede con la misma clave de idempotencia, a la vez.
	 *
	 * <p>409 y no 500: el cliente no hizo nada mal, llego segundo a su propio doble click. No se
	 * puede resolver haciendo replay dentro del mismo request —la sesion de JPA ya no se puede
	 * usar despues del flush que fallo— asi que se le pide reintentar, y el reintento encuentra
	 * el registro y devuelve la sede del ganador.
	 */
	@ExceptionHandler(ConsultorioAltaEnCursoException.class)
	public ProblemDetail handleConsultorioAltaEnCurso(ConsultorioAltaEnCursoException exception) {
		log.info("Alta de sede concurrente con la misma clave: idempotencyKey={}",
				exception.getIdempotencyKey());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Otra solicitud con la misma clave de idempotencia esta en curso. Reintente en "
						+ "unos instantes con la misma clave.");
		problem.setTitle("Alta en curso");
		problem.setType(CONFLICT);
		return problem;
	}

	/** Cuerpo unico de 404: mismo texto para no existe, dado de baja y de otro tenant. */
	private ProblemDetail noEncontrado() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "El recurso solicitado no existe o no esta disponible.");
		problem.setTitle("Recurso no encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}
}
