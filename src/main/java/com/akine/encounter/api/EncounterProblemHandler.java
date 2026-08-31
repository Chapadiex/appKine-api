package com.akine.encounter.api;

import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.platform.spi.problem.ProblemType;
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
 * Traduce las excepciones de {@code encounter} a Problem Details (RFC 7807, ADR-0005).
 *
 * <p>Cada modulo mapea las suyas en su propio advice y nunca en {@code GlobalExceptionHandler}:
 * hacerlo alli obligaria a {@code platform.api} a importar {@code encounter.domain}, cerrando un
 * ciclo entre modulos. Es la regla que 01.01 dejo fijada.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EncounterProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(EncounterProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI TURNO_NO_ATENDIBLE = ProblemType.TURNO_NO_ATENDIBLE.uri();
	private static final URI SESION_AJENA = ProblemType.SESION_AJENA.uri();
	private static final URI VALIDATION_ERROR = ProblemType.VALIDATION_ERROR.uri();

	@ExceptionHandler(ConsultorioNoAccesibleException.class)
	public ProblemDetail handleConsultorioNoAccesible(ConsultorioNoAccesibleException exception) {
		log.debug("Consultorio no accesible desde la atencion: consultorioId={}",
				exception.getConsultorioId());
		return noEncontrado("El consultorio no existe.");
	}

	@ExceptionHandler(SesionNotAccessibleException.class)
	public ProblemDetail handleSesionNoAccesible(SesionNotAccessibleException exception) {
		log.debug("Sesion no accesible: sesionId={}", exception.getSesionId());
		return noEncontrado("La sesion no existe.");
	}

	@ExceptionHandler(TurnoNoAtendibleException.class)
	public ProblemDetail handleTurnoNoAtendible(TurnoNoAtendibleException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(TURNO_NO_ATENDIBLE);
		problem.setTitle("El turno no habilita una atencion");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * <b>409 y no 403, y la diferencia importa.</b> Quien opera SI tiene {@code sesion:register} en
	 * esa sede; lo que no tiene es la propiedad de esta atencion. Un 403 mandaria a la pantalla a
	 * decir "no tenes permiso", que es falso, y mandaria al usuario a pedir un permiso que ya tiene.
	 */
	@ExceptionHandler(SesionAjenaException.class)
	public ProblemDetail handleSesionAjena(SesionAjenaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(SESION_AJENA);
		problem.setTitle("La atencion la registra otro profesional");
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> Un dolor de 12 en una escala de 0 a 10 es un problema del CUERPO
	 * enviado, no del estado del servidor: no depende de nada que pueda cambiar entre dos
	 * peticiones, asi que un 409 —que sugiere reintentar— mandaria al cliente a repetir algo que
	 * va a fallar igual.
	 */
	@ExceptionHandler(EvaluacionIncoherenteException.class)
	public ProblemDetail handleEvaluacionIncoherente(EvaluacionIncoherenteException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(VALIDATION_ERROR);
		problem.setTitle("La evaluacion tiene un dato invalido");
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
