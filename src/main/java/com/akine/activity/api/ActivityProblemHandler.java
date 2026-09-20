package com.akine.activity.api;

import com.akine.activity.application.IdempotencyKeyConflictException;
import com.akine.activity.domain.exception.CapacidadNoAdmitidaException;
import com.akine.activity.domain.exception.ClaseNoProgramableException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.HorarioNoDisponibleException;
import com.akine.activity.domain.exception.OfertaNotAccessibleException;
import com.akine.activity.domain.exception.RecursoOcupadoException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
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
 * Traduce las excepciones de {@code activity} a Problem Details (RFC 7807, ADR-0005).
 *
 * <p><b>Cada modulo mapea las suyas en su propio advice</b> y nunca en
 * {@code GlobalExceptionHandler}: hacerlo alli obligaria a {@code platform.api} a importar
 * {@code activity.domain}, que cierra un ciclo entre modulos. Es la regla que 01.01 dejo fijada.
 *
 * <p><b>Tres tipos nuevos y cuatro reusados.</b> Lo propio de M28 —que la oferta no sostenga una
 * clase, que la transicion no exista, que la capacidad no sea sostenible— tiene tipo propio porque
 * lleva a la pantalla a acciones distintas. Lo que no es propio de M28 —no encontrado, recurso
 * ocupado, horario no disponible, clave de idempotencia reusada— reusa el tipo que ya existe: son
 * la misma situacion, y publicar un segundo codigo para cada una obligaria al cliente a manejar dos
 * para un mismo caso.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActivityProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(ActivityProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI CLASE_NO_PROGRAMABLE = ProblemType.CLASE_NO_PROGRAMABLE.uri();
	private static final URI CLASE_TRANSICION = ProblemType.CLASE_TRANSICION_NO_PERMITIDA.uri();
	private static final URI CLASE_CAPACIDAD = ProblemType.CLASE_CAPACIDAD_NO_ADMITIDA.uri();
	private static final URI RECURSO_OCUPADO = ProblemType.RECURSO_OCUPADO.uri();
	private static final URI SLOT_NO_DISPONIBLE = ProblemType.SLOT_NO_DISPONIBLE.uri();
	private static final URI IDEMPOTENCY_KEY_CONFLICT = ProblemType.IDEMPOTENCY_KEY_CONFLICT.uri();

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
		log.debug("Consultorio no accesible desde clases: consultorioId={}",
				exception.getConsultorioId());
		return noEncontrado("El consultorio no existe.");
	}

	@ExceptionHandler(OfertaNotAccessibleException.class)
	public ProblemDetail handleOfertaNoAccesible(OfertaNotAccessibleException exception) {
		log.debug("Oferta no accesible desde clases: ofertaId={}", exception.getOfertaId());
		return noEncontrado("La oferta no existe en esta sede.");
	}

	@ExceptionHandler(ClaseNotAccessibleException.class)
	public ProblemDetail handleClaseNoAccesible(ClaseNotAccessibleException exception) {
		log.debug("Clase no accesible: claseId={}", exception.getClaseId());
		return noEncontrado("La clase no existe.");
	}

	// =================================================================================
	// Conflicto — 409
	// =================================================================================

	/**
	 * <b>409 y no 404 a proposito.</b> La oferta existe y quien programa la esta viendo en la
	 * lista; contestar 404 mandaria a la pantalla a decir "no encontrada" sobre algo que el usuario
	 * tiene delante de los ojos.
	 *
	 * <p>El motivo viaja porque decide que puede ofrecer la pantalla: "no es grupal" manda a elegir
	 * otra oferta, "no esta vigente" manda a mover la fecha.
	 */
	@ExceptionHandler(ClaseNoProgramableException.class)
	public ProblemDetail handleNoProgramable(ClaseNoProgramableException exception) {
		log.debug("Oferta no programable como clase: ofertaId={} motivo={}",
				exception.getOfertaId(), exception.getMotivo());
		ProblemDetail problem = conflicto(exception.getMessage(), CLASE_NO_PROGRAMABLE);
		problem.setTitle("La oferta no puede sostener una clase");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	@ExceptionHandler(TransicionDeClaseNoPermitidaException.class)
	public ProblemDetail handleTransicion(TransicionDeClaseNoPermitidaException exception) {
		log.debug("Transicion de clase rechazada: claseId={} motivo={}",
				exception.getClaseId(), exception.getMotivo());
		ProblemDetail problem = conflicto(exception.getMessage(), CLASE_TRANSICION);
		problem.setTitle("La clase no admite esa operacion");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * Lleva {@code capacidadMaxima} como propiedad extra, igual que {@code maxDays} en la ventana
	 * de agenda: sin ese numero la pantalla solo puede mostrar el error; con el, puede corregir el
	 * cupo sola. Es la diferencia entre un mensaje y una accion.
	 */
	@ExceptionHandler(CapacidadNoAdmitidaException.class)
	public ProblemDetail handleCapacidad(CapacidadNoAdmitidaException exception) {
		ProblemDetail problem = conflicto(exception.getMessage(), CLASE_CAPACIDAD);
		problem.setTitle("La capacidad pedida no es admisible");
		problem.setProperty("capacidadPedida", exception.getCapacidadPedida());
		problem.setProperty("capacidadMaxima", exception.getCapacidadMaxima());
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * <b>Reusa el tipo de M12.</b> Para la pantalla el desenlace es identico —ese recurso esta
	 * tomado en ese horario— y que el evento conflictivo sea un turno o una clase no cambia lo que
	 * el usuario puede hacer. Lo que si distingue es {@code recurso}.
	 */
	@ExceptionHandler(RecursoOcupadoException.class)
	public ProblemDetail handleRecursoOcupado(RecursoOcupadoException exception) {
		ProblemDetail problem = conflicto(exception.getMessage(), RECURSO_OCUPADO);
		problem.setTitle("El recurso ya esta ocupado en ese horario");
		problem.setProperty("recurso", exception.getRecurso());
		return problem;
	}

	@ExceptionHandler(HorarioNoDisponibleException.class)
	public ProblemDetail handleHorarioNoDisponible(HorarioNoDisponibleException exception) {
		ProblemDetail problem = conflicto(exception.getMessage(), SLOT_NO_DISPONIBLE);
		problem.setTitle("El horario elegido no esta disponible");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	@ExceptionHandler(IdempotencyKeyConflictException.class)
	public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException exception) {
		ProblemDetail problem = conflicto(exception.getMessage(), IDEMPOTENCY_KEY_CONFLICT);
		problem.setTitle("La clave de idempotencia se reuso con otro pedido");
		return problem;
	}

	private static ProblemDetail conflicto(String detalle, URI tipo) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalle);
		problem.setType(tipo);
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
