package com.akine.notification.api;

import com.akine.notification.domain.exception.NotificacionNoEncontradaException;
import com.akine.notification.domain.exception.NotificacionNoReintentableException;
import com.akine.platform.spi.problem.ProblemType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones de {@code notification} a Problem Details, en su propio advice y no en
 * {@code GlobalExceptionHandler}: {@code platform.api} no importa el dominio de otro modulo.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationProblemHandler {

	/** Inexistente, de otra organizacion o sin tenant: <b>404 siempre</b>. */
	@ExceptionHandler(NotificacionNoEncontradaException.class)
	public ProblemDetail handleNoEncontrada(NotificacionNoEncontradaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "La notificacion no existe.");
		problem.setType(ProblemType.NOT_FOUND.uri());
		problem.setTitle("Recurso no encontrado");
		return problem;
	}

	/** Lleva el {@code estado} para que la pantalla diga por que no, en vez de un 409 mudo. */
	@ExceptionHandler(NotificacionNoReintentableException.class)
	public ProblemDetail handleNoReintentable(NotificacionNoReintentableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(ProblemType.CONFLICT.uri());
		problem.setTitle("La notificacion no admite reintento");
		problem.setProperty("estado", exception.getEstado().name());
		return problem;
	}
}
