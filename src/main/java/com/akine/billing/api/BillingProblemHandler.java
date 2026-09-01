package com.akine.billing.api;

import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ObligacionAnuladaException;
import com.akine.billing.domain.exception.ObligacionConCobrosException;
import com.akine.billing.domain.exception.ObligacionNotAccessibleException;
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

/** Traduce las excepciones de {@code billing} a Problem Details. Cada modulo mapea las suyas. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillingProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(BillingProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI OBLIGACION_ALREADY_ANULADA = ProblemType.OBLIGACION_ALREADY_ANULADA.uri();
	private static final URI OBLIGACION_CON_COBROS = ProblemType.OBLIGACION_CON_COBROS.uri();

	@ExceptionHandler(ConsultorioNoAccesibleException.class)
	public ProblemDetail handleConsultorioNoAccesible(ConsultorioNoAccesibleException exception) {
		log.debug("Consultorio no accesible desde deuda: consultorioId={}", exception.getConsultorioId());
		return noEncontrado("El consultorio no existe.");
	}

	@ExceptionHandler(ObligacionNotAccessibleException.class)
	public ProblemDetail handleObligacionNoAccesible(ObligacionNotAccessibleException exception) {
		log.debug("Obligacion no accesible: obligacionId={}", exception.getObligacionId());
		return noEncontrado("La obligacion no existe.");
	}

	@ExceptionHandler(ObligacionAnuladaException.class)
	public ProblemDetail handleYaAnulada(ObligacionAnuladaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(OBLIGACION_ALREADY_ANULADA);
		problem.setTitle("La obligacion ya estaba anulada");
		return problem;
	}

	/**
	 * Lleva {@code yaCobrado} como propiedad extra.
	 *
	 * <p>Sin ese numero la pantalla solo puede decir "no se puede"; con el, puede ofrecer la
	 * devolucion por el importe correcto en vez de dejar al administrativo adivinando.
	 */
	@ExceptionHandler(ObligacionConCobrosException.class)
	public ProblemDetail handleConCobros(ObligacionConCobrosException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(OBLIGACION_CON_COBROS);
		problem.setTitle("La obligacion ya tiene cobros imputados");
		problem.setProperty("yaCobrado", exception.getYaCobrado());
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
