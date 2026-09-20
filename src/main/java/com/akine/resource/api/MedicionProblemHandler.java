package com.akine.resource.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.resource.domain.exception.MedicionDefinicionInactivaException;
import com.akine.resource.domain.exception.MedicionDefinicionNoAccesibleException;
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
 * Traduce las excepciones del catalogo de mediciones a Problem Details (RFC 7807, ADR-0005).
 *
 * <p><b>Advice propio y no dentro de {@code CatalogoProblemHandler}.</b> Aquel cubre los cuatro
 * conceptos de M06 que comparten superclase, pantalla y {@code type}; estos dos son de otra tabla
 * y de otros {@code type}. Meterlos ahi convertiria una clase que hoy se lee de corrido en una
 * que hay que filtrar mentalmente por catalogo.
 *
 * <p>Lo que <b>no</b> se hizo es mapearlas en {@code GlobalExceptionHandler}: eso obligaria a
 * {@code platform.api} a importar {@code resource.domain} y cerraria un ciclo entre modulos. Es la
 * regla que 01.01 dejo fijada.
 *
 * <p>{@code HIGHEST_PRECEDENCE} por el mismo motivo que los demas advices de modulo: sin el,
 * {@code GlobalExceptionHandler} puede ganar la resolucion y contestar un {@code conflict}
 * generico donde el contrato promete un {@code type} preciso.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MedicionProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(MedicionProblemHandler.class);

	private static final URI NO_ACCESIBLE = ProblemType.MEDICION_DEFINICION_NO_ACCESIBLE.uri();
	private static final URI INACTIVA = ProblemType.MEDICION_DEFINICION_INACTIVA.uri();

	/**
	 * Definicion inexistente o de otro tenant.
	 *
	 * <p><b>Los dos casos responden lo mismo, a proposito</b> (ADR-0018). Distinguirlos permitiria
	 * recorrer ids consecutivos y averiguar que tests propios tiene cargados cada centro del SaaS,
	 * que es informacion comercial de sus clientes.
	 */
	@ExceptionHandler(MedicionDefinicionNoAccesibleException.class)
	public ProblemDetail handleNoAccesible(MedicionDefinicionNoAccesibleException exception) {
		log.debug("Definicion de medicion no accesible: definicionId={}",
				exception.getDefinicionId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
				"La definicion de medicion no existe.");
		problem.setType(NO_ACCESIBLE);
		problem.setTitle("Definicion de medicion no encontrada");
		return problem;
	}

	/**
	 * <b>409 y no 404.</b> La definicion existe y se sigue leyendo con 200 —sus mediciones tienen
	 * que seguir siendo legibles—; lo que no admite la operacion es el estado. Responder 404 justo
	 * cuando alguien quiere editarla contradiria la lectura que acaba de devolverla.
	 *
	 * <p><b>No hay reactivacion</b>, asi que el mensaje no la ofrece: una definicion que vuelve es
	 * una definicion nueva.
	 */
	@ExceptionHandler(MedicionDefinicionInactivaException.class)
	public ProblemDetail handleInactiva(MedicionDefinicionInactivaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"La definicion de medicion esta dada de baja. Sus mediciones siguen siendo "
						+ "legibles y comparables; lo que no admite es cambios ni mediciones "
						+ "nuevas. No hay reactivacion: creá una definicion nueva.");
		problem.setType(INACTIVA);
		problem.setTitle("La definicion de medicion esta dada de baja");
		problem.setProperty("definicionId", exception.getDefinicionId());
		return problem;
	}
}
