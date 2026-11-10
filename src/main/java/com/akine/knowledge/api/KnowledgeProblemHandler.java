package com.akine.knowledge.api;

import com.akine.knowledge.domain.exception.BibliotecaDeshabilitadaException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException;
import com.akine.knowledge.domain.exception.LibroDuplicadoException;
import com.akine.knowledge.domain.exception.LibroEnProcesoException;
import com.akine.knowledge.domain.exception.LibroNoEncontradoException;
import com.akine.platform.spi.problem.ProblemType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce las excepciones de {@code knowledge} a Problem Details, en su propio advice. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KnowledgeProblemHandler {

	@ExceptionHandler(BibliotecaDeshabilitadaException.class)
	public ProblemDetail handleDeshabilitada(BibliotecaDeshabilitadaException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.BIBLIOTECA_DESHABILITADA,
				"Biblioteca deshabilitada", exception.getMessage());
	}

	/** Inexistente, de otra organizacion o sin contexto: 404, nunca 403. */
	@ExceptionHandler(LibroNoEncontradoException.class)
	public ProblemDetail handleNoEncontrado(LibroNoEncontradoException exception) {
		return problema(HttpStatus.NOT_FOUND, ProblemType.LIBRO_NO_ENCONTRADO,
				"Libro no encontrado", exception.getMessage());
	}

	@ExceptionHandler(LibroDuplicadoException.class)
	public ProblemDetail handleDuplicado(LibroDuplicadoException exception) {
		ProblemDetail problem = problema(HttpStatus.CONFLICT, ProblemType.LIBRO_DUPLICADO,
				"Libro duplicado", exception.getMessage());
		problem.setProperty("libroId", exception.getLibroId());
		return problem;
	}

	@ExceptionHandler(LibroArchivoNoAceptadoException.class)
	public ProblemDetail handleArchivoNoAceptado(LibroArchivoNoAceptadoException exception) {
		ProblemDetail problem = problema(HttpStatus.BAD_REQUEST, ProblemType.LIBRO_ARCHIVO_NO_ACEPTADO,
				"Archivo no aceptado", exception.getMessage());
		problem.setProperty("causa", exception.getCausa().name());
		return problem;
	}

	@ExceptionHandler(LibroEnProcesoException.class)
	public ProblemDetail handleEnProceso(LibroEnProcesoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.LIBRO_EN_PROCESO,
				"Libro en proceso", exception.getMessage());
	}

	private static ProblemDetail problema(
			HttpStatus status, ProblemType type, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type.uri());
		problem.setTitle(title);
		return problem;
	}
}
