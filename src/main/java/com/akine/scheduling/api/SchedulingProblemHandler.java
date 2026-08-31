package com.akine.scheduling.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;
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
 * Traduce las excepciones de {@code scheduling} a Problem Details (RFC 7807, ADR-0005).
 *
 * <p><b>Cada modulo mapea las suyas en su propio advice</b> y nunca en
 * {@code GlobalExceptionHandler}: hacerlo alli obligaria a {@code platform.api} a importar
 * {@code scheduling.domain}, que cierra un ciclo entre modulos. Es la regla que 01.01 dejo fijada.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchedulingProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(SchedulingProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI OFERTA_NO_AGENDABLE = ProblemType.OFERTA_NO_AGENDABLE.uri();
	private static final URI VENTANA_DEMASIADO_AMPLIA = ProblemType.VENTANA_DEMASIADO_AMPLIA.uri();

	// =================================================================================
	// No accesibles — 404
	// =================================================================================

	/**
	 * <b>404 y nunca 403</b>, aunque la sede exista y sea de otro tenant. Un 403 confirmaria su
	 * existencia, y bastaria probar ids consecutivos para enumerar las sedes de la competencia.
	 * ADR-0018.
	 */
	@ExceptionHandler(ConsultorioNoAccesibleException.class)
	public ProblemDetail handleConsultorioNoAccesible(ConsultorioNoAccesibleException exception) {
		log.debug("Consultorio no accesible desde la agenda: consultorioId={}",
				exception.getConsultorioId());
		return noEncontrado("El consultorio no existe.");
	}

	@ExceptionHandler(OfertaNotAccessibleException.class)
	public ProblemDetail handleOfertaNoAccesible(OfertaNotAccessibleException exception) {
		log.debug("Oferta no accesible desde la agenda: ofertaId={}", exception.getOfertaId());
		return noEncontrado("La oferta no existe en esta sede.");
	}

	// =================================================================================
	// Conflicto — 409
	// =================================================================================

	/**
	 * <b>409 y no 404 a proposito.</b> La oferta existe y quien pregunta la esta viendo en la
	 * lista; contestar 404 mandaria a la pantalla a decir "no encontrada" sobre algo que el usuario
	 * tiene delante de los ojos.
	 *
	 * <p>El motivo viaja en el {@code detail} porque decide que puede ofrecer la pantalla: si la
	 * oferta esta de baja, reactivarla; si es la vigencia, mover la fecha.
	 */
	@ExceptionHandler(OfertaNoAgendableException.class)
	public ProblemDetail handleOfertaNoAgendable(OfertaNoAgendableException exception) {
		log.debug("Oferta no agendable: ofertaId={}", exception.getOfertaId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(OFERTA_NO_AGENDABLE);
		problem.setTitle("La oferta no puede agendar turnos");
		return problem;
	}

	// =================================================================================
	// Peticion invalida — 400
	// =================================================================================

	/**
	 * Lleva {@code maxDays} como propiedad extra, igual que en M05.
	 *
	 * <p>Sin ese numero la pantalla solo puede mostrar el error; con el, puede recortar la ventana
	 * sola y reintentar. Es la diferencia entre un mensaje y una accion.
	 */
	@ExceptionHandler(VentanaDeAgendaDemasiadoAmpliaException.class)
	public ProblemDetail handleVentanaDemasiadoAmplia(
			VentanaDeAgendaDemasiadoAmpliaException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(VENTANA_DEMASIADO_AMPLIA);
		problem.setTitle("La ventana consultada es demasiado amplia");
		problem.setProperty("maxDays", exception.getMaximoDias());
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
