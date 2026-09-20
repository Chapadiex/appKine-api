package com.akine.reporting.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.reporting.domain.exception.ConsultorioNoAccesibleException;
import com.akine.reporting.domain.exception.RangoDeReporteInvalidoException;
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
 * Traduce las excepciones de {@code reporting} a Problem Details.
 *
 * <p><b>Cada modulo mapea las suyas en su propio advice.</b> Hacerlo en
 * {@code GlobalExceptionHandler} obligaria a {@code platform.api} a importar el dominio de este
 * modulo, que es el ciclo que 01.01 prohibio.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReportingProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(ReportingProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI RANGO_INVALIDO = ProblemType.RANGO_DE_REPORTE_INVALIDO.uri();

	/** Sede inexistente o de otro tenant: <b>404 siempre</b>, para no confirmar que existe. */
	@ExceptionHandler(ConsultorioNoAccesibleException.class)
	public ProblemDetail handleConsultorioNoAccesible(ConsultorioNoAccesibleException exception) {
		log.debug("Consultorio no accesible desde reportes: consultorioId={}",
				exception.getConsultorioId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "El consultorio no existe.");
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}

	/**
	 * Periodo invertido o mas ancho que la ventana maxima.
	 *
	 * <p>Lleva {@code maximoDias} como propiedad extra: sin ese numero la pantalla solo puede decir
	 * "el rango es invalido" y dejar al usuario probando hasta acertar.
	 */
	@ExceptionHandler(RangoDeReporteInvalidoException.class)
	public ProblemDetail handleRangoInvalido(RangoDeReporteInvalidoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(RANGO_INVALIDO);
		problem.setTitle("El periodo pedido no sirve para reportar");
		problem.setProperty("maximoDias", exception.getMaximoDias());
		return problem;
	}
}
