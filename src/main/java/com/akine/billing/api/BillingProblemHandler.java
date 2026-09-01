package com.akine.billing.api;

import com.akine.billing.application.IdempotencyKeyConflictException;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.MediosNoSumanException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
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
	private static final URI COBRO_NO_CUADRA = ProblemType.COBRO_NO_CUADRA.uri();
	private static final URI SALDO_INSUFICIENTE = ProblemType.SALDO_INSUFICIENTE.uri();
	private static final URI OBLIGACION_NO_COBRABLE = ProblemType.OBLIGACION_NO_COBRABLE.uri();
	private static final URI IDEMPOTENCY_KEY_CONFLICT = ProblemType.IDEMPOTENCY_KEY_CONFLICT.uri();

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
	// =================================================================================
	// Cobros — M19
	// =================================================================================

	@ExceptionHandler(CobroNotAccessibleException.class)
	public ProblemDetail handleCobroNoAccesible(CobroNotAccessibleException exception) {
		log.debug("Cobro no accesible: cobroId={}", exception.getCobroId());
		return noEncontrado("El cobro no existe.");
	}

	/**
	 * <b>400.</b> Los medios no dan el total.
	 *
	 * <p>Lleva las dos cifras para que la pantalla muestre la diferencia. Sin ellas, el operador
	 * tiene que recontar a mano lo que el servidor ya sumo.
	 */
	@ExceptionHandler(MediosNoSumanException.class)
	public ProblemDetail handleMediosNoSuman(MediosNoSumanException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(COBRO_NO_CUADRA);
		problem.setTitle("Los medios de pago no dan el total");
		problem.setProperty("esperado", exception.getTotal());
		problem.setProperty("recibido", exception.getSumaDeMedios());
		return problem;
	}

	@ExceptionHandler(ImputacionesNoSumanException.class)
	public ProblemDetail handleImputacionesNoSuman(ImputacionesNoSumanException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(COBRO_NO_CUADRA);
		problem.setTitle("Las imputaciones no dan el total");
		problem.setProperty("esperado", exception.getTotal());
		problem.setProperty("recibido", exception.getSumaImputada());
		return problem;
	}

	/**
	 * <b>409 y no 400.</b> El cuerpo era valido cuando se compuso; lo que cambio es el estado del
	 * servidor porque otro cobro se llevo la plata. Reintentar con la cuenta corriente recargada es
	 * la accion correcta, y un 400 sugeriria que el operador se equivoco.
	 */
	@ExceptionHandler(SaldoInsuficienteException.class)
	public ProblemDetail handleSaldoInsuficiente(SaldoInsuficienteException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(SALDO_INSUFICIENTE);
		problem.setTitle("La deuda ya no tiene ese saldo");
		problem.setProperty("obligacionId", exception.getObligacionId());
		problem.setProperty("importeIntentado", exception.getImporteIntentado());
		return problem;
	}

	@ExceptionHandler(ObligacionNoCobrableException.class)
	public ProblemDetail handleNoCobrable(ObligacionNoCobrableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(OBLIGACION_NO_COBRABLE);
		problem.setTitle("La deuda no admite este cobro");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	@ExceptionHandler(IdempotencyKeyConflictException.class)
	public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(IDEMPOTENCY_KEY_CONFLICT);
		problem.setTitle("La clave de idempotencia se reuso con otro pedido");
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
