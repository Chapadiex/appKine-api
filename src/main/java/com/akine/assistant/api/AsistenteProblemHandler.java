package com.akine.assistant.api;

import com.akine.assistant.domain.exception.AsistenteDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoInvalidoException;
import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.exception.SedeNoEncontradaException;
import com.akine.platform.spi.problem.ProblemType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce las excepciones de {@code assistant} a Problem Details, en su propio advice. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AsistenteProblemHandler {

	@ExceptionHandler(AsistenteDeshabilitadoException.class)
	public ProblemDetail handleDeshabilitado(AsistenteDeshabilitadoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.ASISTENTE_DESHABILITADO,
				"Asistente deshabilitado", exception.getMessage());
	}

	@ExceptionHandler(ModeloNoDisponibleException.class)
	public ProblemDetail handleModeloNoDisponible(ModeloNoDisponibleException exception) {
		return problema(HttpStatus.SERVICE_UNAVAILABLE, ProblemType.ASISTENTE_NO_DISPONIBLE,
				"Asistente no disponible", exception.getMessage());
	}

	@ExceptionHandler(DictadoDeshabilitadoException.class)
	public ProblemDetail handleDictadoDeshabilitado(DictadoDeshabilitadoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.DICTADO_DESHABILITADO,
				"Dictado deshabilitado", exception.getMessage());
	}

	@ExceptionHandler(DictadoNoDisponibleException.class)
	public ProblemDetail handleDictadoNoDisponible(DictadoNoDisponibleException exception) {
		return problema(HttpStatus.SERVICE_UNAVAILABLE, ProblemType.DICTADO_NO_DISPONIBLE,
				"Dictado no disponible", exception.getMessage());
	}

	@ExceptionHandler(DictadoInvalidoException.class)
	public ProblemDetail handleDictadoInvalido(DictadoInvalidoException exception) {
		return problema(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_ERROR,
				"Dictado invalido", exception.getMessage());
	}

	/** Inexistente o de otra organizacion: 404, nunca 403. */
	@ExceptionHandler(SedeNoEncontradaException.class)
	public ProblemDetail handleSedeNoEncontrada(SedeNoEncontradaException exception) {
		return problema(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, "Sede no encontrada", exception.getMessage());
	}

	private static ProblemDetail problema(HttpStatus status, ProblemType type, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type.uri());
		problem.setTitle(title);
		return problem;
	}
}
