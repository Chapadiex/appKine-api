package com.akine.platform.api;

import com.akine.platform.spi.problem.ProblemType;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduccion de excepciones a respuestas de error estables (RFC 7807 Problem Details).
 *
 * <p>Fija la convencion de errores del proyecto desde AKINE-00.01, para que ninguna etapa
 * funcional invente su propio formato.
 *
 * <p>Regla innegociable: la respuesta nunca expone detalles internos —stack traces, nombres
 * de clase, SQL, rutas de archivo— porque son informacion util para un atacante e inutil
 * para el cliente. El detalle real va al log, correlacionado por el trace id que Spring
 * incluye en el ProblemDetail.
 *
 * <h2>Que codigo corresponde a que</h2>
 * <ul>
 *   <li><b>404 {@code not-found}</b> — el recurso no existe, esta dado de baja, o es de otro
 *       tenant. <b>Los tres se responden igual, a proposito.</b> Un 403 sobre un recurso ajeno
 *       confirmaria que existe, y alcanzaria con probar ids consecutivos para enumerar los
 *       clientes del SaaS. Para quien no tiene acceso, el recurso simplemente no existe.</li>
 *   <li><b>403 {@code forbidden}</b> — el actor esta dentro de su alcance pero le falta el
 *       permiso. Es el unico caso donde negar no revela nada que el actor no supiera ya.</li>
 *   <li><b>409</b> — el pedido es legitimo pero choca con el estado actual: una transicion que
 *       la maquina de estados no admite, una version desactualizada, un limite de plan
 *       alcanzado.</li>
 * </ul>
 *
 * <h2>Aca no se emite ningun 401</h2>
 * <b>No es una omision.</b> El interceptor del frontend hace
 * {@code if (error.status === 401) tokenStore.clear()}: un 401 emitido por un endpoint de
 * negocio le borraria el token a un usuario que SI esta autenticado y lo mandaria a un bucle de
 * login. Quien decide "no se quien sos" es el punto de entrada de autenticacion, que llega en
 * 01.02. Falta de contexto y falta de permiso son ambas condiciones de autorizacion: 403.
 *
 * <h2>Nota de arquitectura</h2>
 * Las excepciones de dominio de los modulos funcionales se mapean en el advice de cada modulo
 * —ver {@code com.akine.organization.api.OrganizationProblemHandler}— y no aca: {@code platform}
 * es el modulo base y una flecha {@code platform -> organization} cerraria un ciclo que
 * ArchUnit rechaza. Lo que si vive aca es todo lo generico: validacion, permisos, concurrencia,
 * integridad y la red de contencion.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final URI VALIDATION_ERROR = ProblemType.VALIDATION_ERROR.uri();
	private static final URI FORBIDDEN = ProblemType.FORBIDDEN.uri();
	private static final URI CONFLICT = ProblemType.CONFLICT.uri();
	private static final URI INTERNAL_ERROR = ProblemType.INTERNAL_ERROR.uri();

	/**
	 * Falla de validacion de un {@code @Valid}. Devuelve los campos rechazados: es
	 * informacion que el cliente necesita para corregir, y no revela nada interno.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "La solicitud contiene campos invalidos");
		problem.setTitle("Error de validacion");
		problem.setType(VALIDATION_ERROR);

		Map<String, String> errors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors().forEach(fieldError ->
				errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
		problem.setProperty("errors", errors);

		return problem;
	}

	/**
	 * Falta un header obligatorio del contrato.
	 *
	 * <p>El caso concreto de 01.01 es {@code Idempotency-Key} en el alta de organizaciones. Se
	 * nombra el header en la respuesta porque es exactamente lo que el cliente tiene que
	 * corregir, y el nombre ya esta publicado en el contrato: no revela nada.
	 */
	@ExceptionHandler(MissingRequestHeaderException.class)
	public ProblemDetail handleMissingHeader(MissingRequestHeaderException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"Falta el header obligatorio '" + exception.getHeaderName() + "'");
		problem.setTitle("Header obligatorio ausente");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	/**
	 * Un valor de la URL o de la query no tiene el tipo que declara el contrato.
	 *
	 * <p>El caso tipico es un {@code {orgId}} que no es un numero. Sin este handler la
	 * excepcion caia en la red de contencion de {@code Exception} y un error del cliente se
	 * devolvia como <b>500</b>: contradice el contrato —que en esas rutas solo declara 200, 403
	 * y 404— y despierta a operaciones por una URL mal tipeada.
	 *
	 * <p>Se nombra el parametro pero <b>no se repite el valor recibido</b>: viene del atacante y
	 * reflejarlo en la respuesta es el vector clasico de XSS reflejado.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"El parametro '" + exception.getName() + "' no tiene el formato esperado");
		problem.setTitle("Parametro invalido");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	/**
	 * El cuerpo del request no se puede leer: JSON sintacticamente roto, un tipo que no
	 * corresponde —{@code "organizationId": "hola"} donde se espera un numero—, un cuerpo
	 * vacio donde el contrato exige uno.
	 *
	 * <p>Sin este handler la excepcion caia en la red de contencion de {@code Exception} y un
	 * JSON mal armado por el cliente se devolvia como <b>500</b>. No filtraba internals, pero
	 * despertaba a operaciones por un error que no es del servidor y contradecia el contrato,
	 * que en esas rutas no declara ningun 500.
	 *
	 * <p><b>El mensaje de la excepcion no se propaga.</b> Trae el fragmento del cuerpo recibido,
	 * la posicion del caracter y el nombre de la clase Java que se estaba deserializando: lo
	 * primero viene del atacante y reflejarlo es XSS reflejado, lo ultimo es un mapa del modelo
	 * interno. El detalle util va al log.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
		log.info("Cuerpo del request ilegible: {}", exception.getMessage());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"El cuerpo de la solicitud no se puede interpretar. Verifique que sea JSON "
						+ "valido y que cada campo tenga el tipo que declara el contrato.");
		problem.setTitle("Cuerpo invalido");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	/**
	 * Todo lo que Spring MVC ya sabe clasificar por si mismo: {@code Content-Type} no soportado
	 * (415), metodo no permitido en esa ruta (405), {@code Accept} que no se puede satisfacer
	 * (406), ruta inexistente (404), parametro de query obligatorio ausente (400)...
	 *
	 * <p>Se manejan juntos porque todos implementan {@link ErrorResponse} y <b>ya traen el
	 * estado correcto</b>: lo unico que faltaba era no dejar que la red de contencion de
	 * {@code Exception} los convirtiera en 500. Un {@code Content-Type: text/plain} contra un
	 * endpoint JSON respondia 500 en vez de 415, y eso es un error del cliente disfrazado de
	 * incidente de produccion.
	 *
	 * <p>Se toma el <b>estado</b> de la excepcion y nada mas: el cuerpo se arma aca, con un
	 * texto fijo por familia de estado. El {@code ProblemDetail} que trae Spring incluye lo que
	 * llego en el request —el {@code Content-Type} rechazado, el metodo usado, la ruta— y esa
	 * es informacion del atacante que no se refleja. Los headers que si son parte del contrato,
	 * como {@code Allow} o {@code Accept}, los sigue poniendo Spring en la respuesta.
	 *
	 * <p>Los handlers especificos de arriba ganan sobre este aunque su excepcion tambien
	 * implemente {@code ErrorResponse}: Spring resuelve por cercania en la jerarquia de clases y
	 * una coincidencia por interfaz es siempre la mas lejana.
	 */
	@ExceptionHandler({
			HttpMediaTypeNotSupportedException.class,
			HttpMediaTypeNotAcceptableException.class,
			HttpRequestMethodNotSupportedException.class,
			MissingServletRequestParameterException.class,
			NoResourceFoundException.class,
			ErrorResponseException.class})
	public ResponseEntity<ProblemDetail> handleProtocolo(Exception exception) {
		// Todas las declaradas arriba implementan ErrorResponse; el parametro es Exception
		// porque @ExceptionHandler solo admite tipos que sean Throwable, y ErrorResponse es
		// una interfaz que no lo es.
		ErrorResponse respuesta = (ErrorResponse) exception;
		HttpStatusCode status = respuesta.getStatusCode();
		log.info("Request rechazado por el protocolo: status={}", status.value());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detalleDe(status));
		problem.setTitle("Solicitud no procesable");
		problem.setType(status.is4xxClientError() ? VALIDATION_ERROR : INTERNAL_ERROR);
		return ResponseEntity.status(status).headers(respuesta.getHeaders()).body(problem);
	}

	/** Texto fijo por caso. Nunca incluye nada de lo que llego en el request. */
	private String detalleDe(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> "El recurso solicitado no existe o no esta disponible.";
			case 405 -> "El metodo HTTP no esta permitido en esta ruta.";
			case 406 -> "No se puede responder en ninguno de los formatos aceptados.";
			case 415 -> "El tipo de contenido enviado no es compatible con esta operacion.";
			default -> "La solicitud no se puede procesar tal como fue enviada.";
		};
	}

	/**
	 * Argumento rechazado por una regla que no se puede expresar con anotaciones.
	 *
	 * <p>Ejemplo de 01.01: el motivo obligatorio al suspender o cancelar una suscripcion, que
	 * depende del estado destino y por eso no es un {@code @NotBlank}. El mensaje de estas
	 * excepciones lo escribe el propio dominio y esta redactado para el usuario; se propaga tal
	 * cual porque decir "argumento invalido" a secas dejaria al cliente sin saber que corregir.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setTitle("Solicitud invalida");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	/**
	 * El actor esta dentro de su alcance pero no tiene el permiso.
	 *
	 * <p>Deliberadamente 403 y no 401: quien llega aca esta identificado. Y deliberadamente 403
	 * y no 404: negarle una accion sobre un recurso que ya sabe que existe no le revela nada
	 * nuevo. El 404 se reserva para lo que es de otro tenant.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
		log.info("Acceso denegado: {}", exception.getMessage());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.FORBIDDEN, "No tiene permiso para realizar esta operacion");
		problem.setTitle("Acceso denegado");
		problem.setType(FORBIDDEN);
		return problem;
	}

	/**
	 * La version enviada por el cliente quedo vieja, o el estado actual no es el esperado.
	 *
	 * <p>No es un fallo del servidor ni del cliente: dos operaciones concurrentes tocaron el
	 * mismo recurso. La respuesta correcta es pedir releer y reintentar, no pisar el cambio de
	 * otro en silencio.
	 */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ProblemDetail handleOptimisticLocking(OptimisticLockingFailureException exception) {
		log.info("Conflicto de concurrencia: {}", exception.getMessage());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El recurso fue modificado por otra operacion. Vuelva a leerlo y reintente.");
		problem.setTitle("Conflicto de concurrencia");
		problem.setType(CONFLICT);
		return problem;
	}

	/**
	 * Choque contra una restriccion de la base: unicidad, clave foranea, nulos.
	 *
	 * <p>El caso previsto en 01.01 es el slug de organizacion duplicado. <b>El mensaje de la
	 * excepcion no se propaga jamas:</b> trae el SQL, el nombre del indice y a veces el valor
	 * que choco. Eso va al log, donde sirve; en la respuesta seria un mapa del esquema.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception) {
		log.warn("Violacion de integridad al persistir", exception);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La operacion choca con un dato ya existente. Verifique los identificadores unicos.");
		problem.setTitle("Conflicto de datos");
		problem.setType(CONFLICT);
		return problem;
	}

	/**
	 * Red de contencion. Todo lo que llegue aca es un bug: se loguea completo y se
	 * responde generico.
	 */
	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception exception) {
		log.error("Excepcion no controlada", exception);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrio un error inesperado");
		problem.setTitle("Error interno");
		problem.setType(INTERNAL_ERROR);
		return problem;
	}
}
