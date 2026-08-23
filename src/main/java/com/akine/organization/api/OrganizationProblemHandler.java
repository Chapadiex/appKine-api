package com.akine.organization.api;

import com.akine.organization.application.IdempotencyKeyConflictException;
import com.akine.organization.application.PlanNotFoundException;
import com.akine.organization.domain.exception.ContextNotAuthorizedException;
import com.akine.organization.domain.exception.FeatureNotAvailableException;
import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
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

	private static final URI NOT_FOUND = URI.create("https://akine.app/problems/not-found");
	private static final URI INVALID_SUBSCRIPTION_TRANSITION =
			URI.create("https://akine.app/problems/invalid-subscription-transition");
	private static final URI PLAN_LIMIT_EXCEEDED =
			URI.create("https://akine.app/problems/plan-limit-exceeded");
	private static final URI FEATURE_NOT_AVAILABLE =
			URI.create("https://akine.app/problems/feature-not-available");
	private static final URI SUBSCRIPTION_SUSPENDED =
			URI.create("https://akine.app/problems/subscription-suspended");
	private static final URI IDEMPOTENCY_KEY_CONFLICT =
			URI.create("https://akine.app/problems/idempotency-key-conflict");

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

	/** Cuerpo unico de 404: mismo texto para no existe, dado de baja y de otro tenant. */
	private ProblemDetail noEncontrado() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "El recurso solicitado no existe o no esta disponible.");
		problem.setTitle("Recurso no encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}
}
