package com.akine.platform.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
 * <p>Los modulos funcionales agregan aca sus excepciones de dominio a medida que existan.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final URI VALIDATION_ERROR = URI.create("https://akine.app/problems/validation-error");
	private static final URI INTERNAL_ERROR = URI.create("https://akine.app/problems/internal-error");

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
