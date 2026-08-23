package com.akine.identity.api;

import com.akine.identity.domain.exception.AccountNotFoundException;
import com.akine.identity.domain.exception.ContextNotAvailableException;
import com.akine.identity.domain.exception.InvalidAccountTransitionException;
import com.akine.identity.domain.exception.InvalidCredentialsException;
import com.akine.identity.domain.exception.InvalidRefreshTokenException;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.exception.PasswordPolicyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Traduccion de las excepciones de {@code identity} a Problem Details.
 *
 * <h2>Por que no esta en {@code GlobalExceptionHandler}</h2>
 *
 * <b>No es duplicacion: es la unica ubicacion legal.</b> {@code GlobalExceptionHandler} vive en
 * {@code platform}, que es el modulo base. Mapear ahi estas excepciones exige importarlas, y
 * eso crea una flecha {@code platform -> identity.domain} que cierra un ciclo —{@code identity}
 * ya depende de {@code platform.spi} para la auditoria, el contexto y la emision de tokens—.
 * {@code ModuleArchitectureTest} lo caza con {@code modulos_solo_se_alcanzan_por_su_spi} y con
 * {@code sin_ciclos_entre_modulos}. Lo que se comparte con {@code platform} es la
 * <b>convencion</b>: mismo RFC 7807, mismos {@code type} bajo {@code https://akine.app/problems/}
 * y la misma regla de no filtrar nada interno. Es el mismo criterio que
 * {@code OrganizationProblemHandler}.
 *
 * <h2>Orden</h2>
 *
 * {@code HIGHEST_PRECEDENCE}, igual que el advice de {@code organization} y por el mismo
 * motivo: {@code GlobalExceptionHandler} tiene un manejador de {@code Exception} como red de
 * contencion y Spring resuelve por advice antes que por especificidad. Sin este orden, cada una
 * de estas excepciones saldria como 500. Los dos advices manejan tipos disjuntos, asi que
 * compartir precedencia no los enfrenta.
 *
 * <h2>La regla que gobierna todo este archivo</h2>
 *
 * <p><b>Ningun cuerpo dice por que fallo.</b> "Usuario inexistente", "contrasena incorrecta",
 * "cuenta bloqueada", "token vencido" y "refresh reusado" son cinco mensajes distintos que
 * responden preguntas que nadie deberia poder hacerle a este endpoint (ADR-0018). Salen dos
 * textos fijos: uno para credenciales y otro para tokens. La diferencia real vive en la
 * auditoria —{@code LOGIN_FALLIDO}, {@code LOGIN_RECHAZADO_ESTADO},
 * {@code REFRESH_REUSO_DETECTADO}— que es donde se puede investigar sin devolversela a quien
 * pregunta.
 *
 * <p>La unica excepcion deliberada es {@link PasswordPolicyViolationException}: ahi el mensaje
 * si es explicito, porque habla de lo que la persona acaba de tipear y no de nada que exista en
 * la base.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(IdentityProblemHandler.class);

	private static final String BASE = "https://akine.app/problems/";

	private static final URI INVALID_CREDENTIALS = URI.create(BASE + "invalid-credentials");
	private static final URI INVALID_REFRESH = URI.create(BASE + "invalid-refresh");
	private static final URI INVALID_TOKEN = URI.create(BASE + "invalid-token");
	private static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
	private static final URI NOT_FOUND = URI.create(BASE + "not-found");
	private static final URI CONFLICT = URI.create(BASE + "conflict");

	/**
	 * Credenciales rechazadas (RF-M02-002, ADR-0018).
	 *
	 * <p>Un email sin cuenta, una contrasena incorrecta y una contrasena <b>correcta</b> sobre
	 * una cuenta bloqueada, desactivada o pendiente de activacion salen los tres por aca, con el
	 * mismo estado y el mismo cuerpo. El tercero es el que incomoda y es el que mas importa:
	 * responder "cuenta bloqueada" convertiria el login en un verificador de credenciales
	 * filtradas de otros sitios.
	 *
	 * <p>Ni siquiera se loguea el email: el servicio ya registro el intento en la auditoria con
	 * la granularidad correcta, y repetirlo aca lo duplicaria en el log de aplicacion, que es
	 * mas facil de exportar.
	 */
	@ExceptionHandler(InvalidCredentialsException.class)
	public ProblemDetail handleInvalidCredentials(InvalidCredentialsException exception) {
		log.debug("Login rechazado");
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNAUTHORIZED,
				"Email o contrasena incorrectos.");
		problem.setTitle("Credenciales invalidas");
		problem.setType(INVALID_CREDENTIALS);
		return problem;
	}

	/**
	 * Refresh inservible: inexistente, vencido, revocado o <b>reusado</b> (ADR-0017).
	 *
	 * <p>El reuso —dos portadores de la misma cadena, o sea una cookie robada— ya provoco la
	 * revocacion de la familia completa dentro del servicio. Hacia afuera es indistinguible de
	 * un token cualquiera que no sirve: decirle al atacante que su copia fue detectada le
	 * regala la unica informacion que necesita para ajustar el ataque.
	 *
	 * <p>La respuesta <b>borra la cookie</b>. Sin eso el navegador seguiria presentando un
	 * refresh muerto en cada intento, y el frontend quedaria en un bucle de 401 con una cookie
	 * que ya no sirve para nada.
	 */
	@ExceptionHandler(InvalidRefreshTokenException.class)
	public ResponseEntity<ProblemDetail> handleInvalidRefresh(InvalidRefreshTokenException exception) {
		log.debug("Refresh rechazado");
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNAUTHORIZED,
				"La sesion no es valida o expiro. Volve a iniciar sesion.");
		problem.setTitle("Sesion invalida");
		problem.setType(INVALID_REFRESH);

		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.SET_COOKIE, RefreshCookies.borrar())
				.body(problem);
	}

	/**
	 * Contexto de trabajo no disponible para esta cuenta.
	 *
	 * <p><b>404, jamas 403.</b> Regla heredada de 01.01 y reafirmada por ADR-0019: un 403
	 * confirmaria que ese consultorio existe pero es de otro, y bastaria recorrer ids
	 * consecutivos para mapear los centros de la competencia. Consultorio inexistente,
	 * consultorio ajeno, membership vencida y suscripcion cancelada responden lo mismo.
	 *
	 * <p>Los ids pedidos van al log y nunca a la respuesta.
	 */
	@ExceptionHandler(ContextNotAvailableException.class)
	public ProblemDetail handleContextNotAvailable(ContextNotAvailableException exception) {
		log.info("Contexto no disponible: organizationId={} consultorioId={}",
				exception.getOrganizationId(), exception.getConsultorioId());
		return noEncontrado();
	}

	/**
	 * Token de activacion o de reset que no sirve (ADR-0018).
	 *
	 * <p>Inexistente, ya usado, invalidado por uno mas nuevo, vencido, o del tipo equivocado:
	 * los cinco casos, el mismo 400 y el mismo texto. Responder "expirado" le confirmaria a
	 * quien prueba valores que acerto uno real, que es justo lo que necesita saber.
	 *
	 * <p>400 y no 404: el recurso al que se apunta es la propia solicitud, y lo invalido es lo
	 * que se envio. Un 404 ademas invitaria al frontend a mostrar una pantalla de "no existe"
	 * en vez de la de "pedi un enlace nuevo", que es la accion util.
	 */
	@ExceptionHandler(InvalidVerificationTokenException.class)
	public ProblemDetail handleInvalidVerificationToken(InvalidVerificationTokenException exception) {
		log.debug("Token de verificacion rechazado");
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"El enlace no es valido o ya vencio. Pedi uno nuevo.");
		problem.setTitle("Enlace invalido");
		problem.setType(INVALID_TOKEN);
		return problem;
	}

	/**
	 * La contrasena propuesta no cumple la politica (RNF-M02-008).
	 *
	 * <p><b>Aca el mensaje SI es explicito</b>, y es la unica excepcion a la regla de arriba: no
	 * hay nada que enumerar porque el motivo habla de lo que la persona acaba de tipear, no de
	 * si existe algo en la base. Un "invalida" a secas la dejaria adivinando que le falta.
	 */
	@ExceptionHandler(PasswordPolicyViolationException.class)
	public ProblemDetail handlePasswordPolicy(PasswordPolicyViolationException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setTitle("Contrasena rechazada por la politica");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	/**
	 * Cuenta inexistente, o de una organizacion que no es la del actor (ADR-0019).
	 *
	 * <p>Los dos casos, el mismo 404. Distinguirlos dejaria que un administrador enumere las
	 * cuentas de organizaciones ajenas preguntando por ids consecutivos, que es la version
	 * cross-tenant del mismo problema que resuelve la regla de 01.01.
	 */
	@ExceptionHandler(AccountNotFoundException.class)
	public ProblemDetail handleAccountNotFound(AccountNotFoundException exception) {
		log.debug("Cuenta no alcanzable para el actor");
		return noEncontrado();
	}

	/**
	 * Transicion que la maquina de estados de la cuenta no admite.
	 *
	 * <p>409 y no 403: el actor tiene derecho a la operacion, lo que no se puede es aplicarla
	 * desde el estado actual. Se publican origen y destino porque el destino lo eligio quien
	 * llama y el origen es una cuenta que ya puede ver: no es informacion nueva, y sin ella el
	 * mensaje seria inaccionable.
	 *
	 * <p>Bloquear una cuenta ya bloqueada entra aca a proposito, en vez de responder un 200
	 * silencioso: dos administradores operando a la vez tienen que enterarse de que el otro
	 * llego primero.
	 */
	@ExceptionHandler(InvalidAccountTransitionException.class)
	public ProblemDetail handleInvalidTransition(InvalidAccountTransitionException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La cuenta no admite pasar de " + exception.getDesde()
						+ " a " + exception.getHacia() + ".");
		problem.setTitle("Transicion de cuenta no permitida");
		problem.setType(CONFLICT);
		problem.setProperty("fromStatus", exception.getDesde().name());
		problem.setProperty("toStatus", exception.getHacia().name());
		return problem;
	}

	/** Cuerpo unico de 404: mismo texto para no existe, no accesible y de otro tenant. */
	private ProblemDetail noEncontrado() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "El recurso solicitado no existe o no esta disponible.");
		problem.setTitle("Recurso no encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}
}
