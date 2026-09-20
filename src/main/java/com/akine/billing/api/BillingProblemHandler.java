package com.akine.billing.api;

import com.akine.billing.application.IdempotencyKeyConflictException;
import com.akine.billing.domain.exception.BeneficiarioNoVinculadoException;
import com.akine.billing.domain.exception.EgresoComprobanteDuplicadoException;
import com.akine.billing.domain.exception.EgresoConPagosException;
import com.akine.billing.domain.exception.EgresoNoConfirmableException;
import com.akine.billing.domain.exception.EgresoNoEditableException;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.exception.EgresoNotAccessibleException;
import com.akine.billing.domain.exception.EgresoSaldoInsuficienteException;
import com.akine.billing.domain.exception.EgresoSinComprobanteException;
import com.akine.billing.domain.exception.EgresoYaAnuladoException;
import com.akine.billing.domain.exception.PagoEgresoNotAccessibleException;
import com.akine.billing.domain.exception.PagoEgresoYaAnuladoException;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaDiferenciaSinMotivoException;
import com.akine.billing.domain.exception.CajaMonedaDistintaException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CajaSaldoCambioException;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import com.akine.billing.domain.exception.CajaYaAbiertaException;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.JornadaCajaNotAccessibleException;
import com.akine.billing.domain.exception.MovimientoCajaNotAccessibleException;
import com.akine.billing.domain.exception.MovimientoNoReversibleException;
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
	private static final URI CAJA_NO_ABIERTA = ProblemType.CAJA_NO_ABIERTA.uri();
	private static final URI CAJA_YA_ABIERTA = ProblemType.CAJA_YA_ABIERTA.uri();
	private static final URI CAJA_CERRADA = ProblemType.CAJA_CERRADA.uri();
	private static final URI CAJA_SALDO_CAMBIO = ProblemType.CAJA_SALDO_CAMBIO.uri();
	private static final URI CAJA_SALDO_INSUFICIENTE = ProblemType.CAJA_SALDO_INSUFICIENTE.uri();
	private static final URI CAJA_MONEDA_DISTINTA = ProblemType.CAJA_MONEDA_DISTINTA.uri();
	private static final URI MOVIMIENTO_NO_REVERSIBLE = ProblemType.MOVIMIENTO_NO_REVERSIBLE.uri();
	private static final URI CAJA_DIFERENCIA_SIN_MOTIVO = ProblemType.CAJA_DIFERENCIA_SIN_MOTIVO.uri();
	private static final URI EGRESO_NO_EDITABLE = ProblemType.EGRESO_NO_EDITABLE.uri();
	private static final URI EGRESO_NO_CONFIRMABLE = ProblemType.EGRESO_NO_CONFIRMABLE.uri();
	private static final URI EGRESO_SIN_COMPROBANTE = ProblemType.EGRESO_SIN_COMPROBANTE.uri();
	private static final URI EGRESO_YA_ANULADO = ProblemType.EGRESO_YA_ANULADO.uri();
	private static final URI EGRESO_CON_PAGOS = ProblemType.EGRESO_CON_PAGOS.uri();
	private static final URI EGRESO_NO_PAGABLE = ProblemType.EGRESO_NO_PAGABLE.uri();
	private static final URI EGRESO_SALDO_INSUFICIENTE = ProblemType.EGRESO_SALDO_INSUFICIENTE.uri();
	private static final URI EGRESO_COMPROBANTE_DUPLICADO = ProblemType.EGRESO_COMPROBANTE_DUPLICADO.uri();
	private static final URI PAGO_EGRESO_YA_ANULADO = ProblemType.PAGO_EGRESO_YA_ANULADO.uri();
	private static final URI BENEFICIARIO_NO_VINCULADO = ProblemType.BENEFICIARIO_NO_VINCULADO.uri();

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

	// =================================================================================
	// Caja diaria — M20
	// =================================================================================

	@ExceptionHandler(JornadaCajaNotAccessibleException.class)
	public ProblemDetail handleJornadaNoAccesible(JornadaCajaNotAccessibleException exception) {
		log.debug("Jornada de caja no accesible: jornadaId={}", exception.getJornadaId());
		return noEncontrado("La jornada de caja no existe.");
	}

	@ExceptionHandler(MovimientoCajaNotAccessibleException.class)
	public ProblemDetail handleMovimientoNoAccesible(MovimientoCajaNotAccessibleException exception) {
		log.debug("Movimiento de caja no accesible: movimientoId={}", exception.getMovimientoId());
		return noEncontrado("El movimiento de caja no existe.");
	}

	/**
	 * <b>409.</b> No hay caja abierta.
	 *
	 * <p>Puede llegar desde el registro de un cobro y no solo desde la caja, y por eso el detalle
	 * nombra la sede: la pantalla tiene que poder ofrecer "abrir caja" en vez de decir "no se
	 * puede", que dejaria al administrativo trabado sin entender por que.
	 */
	@ExceptionHandler(CajaNoAbiertaException.class)
	public ProblemDetail handleCajaNoAbierta(CajaNoAbiertaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(CAJA_NO_ABIERTA);
		problem.setTitle("No hay una caja abierta en esta sede");
		problem.setProperty("consultorioId", exception.getConsultorioId());
		return problem;
	}

	/** <b>409.</b> Lleva el id de la que ya esta abierta, para poder llevar al operador ahi. */
	@ExceptionHandler(CajaYaAbiertaException.class)
	public ProblemDetail handleCajaYaAbierta(CajaYaAbiertaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(CAJA_YA_ABIERTA);
		problem.setTitle("La sede ya tiene una caja abierta");
		problem.setProperty("jornadaAbiertaId", exception.getJornadaAbiertaId());
		return problem;
	}

	@ExceptionHandler(CajaCerradaException.class)
	public ProblemDetail handleCajaCerrada(CajaCerradaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(CAJA_CERRADA);
		problem.setTitle("La jornada de caja ya esta cerrada");
		problem.setProperty("jornadaId", exception.getJornadaId());
		return problem;
	}

	/**
	 * <b>409.</b> Entraron movimientos mientras el operador contaba.
	 *
	 * <p>Lleva el teorico actual porque sin el la pantalla solo puede decir "volve a intentar", y el
	 * operador reintentaria exactamente lo mismo. Con el, puede decir cuanto entro y ofrecer
	 * confirmar contra el numero nuevo.
	 */
	@ExceptionHandler(CajaSaldoCambioException.class)
	public ProblemDetail handleCajaSaldoCambio(CajaSaldoCambioException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(CAJA_SALDO_CAMBIO);
		problem.setTitle("El saldo teorico cambio mientras se contaba");
		problem.setProperty("jornadaId", exception.getJornadaId());
		problem.setProperty("saldoTeoricoEsperado", exception.getSaldoTeoricoEsperado());
		problem.setProperty("saldoTeoricoActual", exception.getSaldoTeoricoActual());
		return problem;
	}

	@ExceptionHandler(CajaSaldoInsuficienteException.class)
	public ProblemDetail handleCajaSaldoInsuficiente(CajaSaldoInsuficienteException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(CAJA_SALDO_INSUFICIENTE);
		problem.setTitle("La caja no tiene ese saldo");
		problem.setProperty("jornadaId", exception.getJornadaId());
		problem.setProperty("importeIntentado", exception.getImporteIntentado());
		problem.setProperty("saldoDisponible", exception.getSaldoDisponible());
		return problem;
	}

	@ExceptionHandler(CajaMonedaDistintaException.class)
	public ProblemDetail handleCajaMonedaDistinta(CajaMonedaDistintaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(CAJA_MONEDA_DISTINTA);
		problem.setTitle("La caja opera en otra moneda");
		problem.setProperty("monedaDeLaCaja", exception.getMonedaDeLaCaja());
		problem.setProperty("monedaDelMovimiento", exception.getMonedaDelMovimiento());
		return problem;
	}

	@ExceptionHandler(MovimientoNoReversibleException.class)
	public ProblemDetail handleMovimientoNoReversible(MovimientoNoReversibleException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(MOVIMIENTO_NO_REVERSIBLE);
		problem.setTitle("El movimiento de caja no admite reversion");
		problem.setProperty("movimientoId", exception.getMovimientoId());
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> El estado del servidor esta perfecto; falta un campo del cuerpo.
	 *
	 * <p>Lleva la diferencia calculada para que la pantalla pueda decir cuanto falta o cuanto sobra
	 * mientras pide el motivo.
	 */
	@ExceptionHandler(CajaDiferenciaSinMotivoException.class)
	public ProblemDetail handleDiferenciaSinMotivo(CajaDiferenciaSinMotivoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(CAJA_DIFERENCIA_SIN_MOTIVO);
		problem.setTitle("El arqueo no cuadra y no declara motivo");
		problem.setProperty("diferencia", exception.getDiferencia());
		return problem;
	}

	// =================================================================================
	// Egresos y pagos a profesionales — M22
	//
	// Los tipos de la CAJA no se repiten aca: un pago en efectivo sin jornada abierta, o que
	// no entra en el cajon, sale por los handlers de M20 de mas arriba. Es el mismo ledger.
	// =================================================================================

	@ExceptionHandler(EgresoNotAccessibleException.class)
	public ProblemDetail handleEgresoNoAccesible(EgresoNotAccessibleException exception) {
		log.debug("Egreso no accesible: egresoId={}", exception.getEgresoId());
		return noEncontrado("El egreso no existe.");
	}

	@ExceptionHandler(PagoEgresoNotAccessibleException.class)
	public ProblemDetail handlePagoEgresoNoAccesible(PagoEgresoNotAccessibleException exception) {
		log.debug("Pago de egreso no accesible: pagoId={}", exception.getPagoId());
		return noEncontrado("El pago de egreso no existe.");
	}

	@ExceptionHandler(EgresoNoEditableException.class)
	public ProblemDetail handleEgresoNoEditable(EgresoNoEditableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_NO_EDITABLE);
		problem.setTitle("El egreso ya no se edita");
		problem.setProperty("estado", exception.getEstado());
		return problem;
	}

	@ExceptionHandler(EgresoNoConfirmableException.class)
	public ProblemDetail handleEgresoNoConfirmable(EgresoNoConfirmableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_NO_CONFIRMABLE);
		problem.setTitle("El egreso no se puede confirmar");
		problem.setProperty("estado", exception.getEstado());
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> El estado del servidor esta perfecto; falta un campo del cuerpo.
	 *
	 * <p>Mismo criterio que {@code caja-diferencia-sin-motivo}: la diferencia entre "no se puede
	 * ahora" y "faltan datos" es lo que le dice a la pantalla si tiene que recargar o pedir algo.
	 */
	@ExceptionHandler(EgresoSinComprobanteException.class)
	public ProblemDetail handleEgresoSinComprobante(EgresoSinComprobanteException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(EGRESO_SIN_COMPROBANTE);
		problem.setTitle("El egreso no declara comprobante");
		problem.setProperty("egresoId", exception.getEgresoId());
		return problem;
	}

	@ExceptionHandler(EgresoYaAnuladoException.class)
	public ProblemDetail handleEgresoYaAnulado(EgresoYaAnuladoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_YA_ANULADO);
		problem.setTitle("El egreso ya estaba anulado");
		return problem;
	}

	/**
	 * Lleva {@code yaPagado} como propiedad extra.
	 *
	 * <p>Sin ese numero la pantalla solo puede decir "no se puede"; con el puede nombrar la accion
	 * correcta —anular primero los pagos— en vez de dejar al administrativo adivinando.
	 */
	@ExceptionHandler(EgresoConPagosException.class)
	public ProblemDetail handleEgresoConPagos(EgresoConPagosException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_CON_PAGOS);
		problem.setTitle("El egreso ya tiene pagos vigentes");
		problem.setProperty("yaPagado", exception.getYaPagado());
		return problem;
	}

	@ExceptionHandler(EgresoNoPagableException.class)
	public ProblemDetail handleEgresoNoPagable(EgresoNoPagableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_NO_PAGABLE);
		problem.setTitle("El egreso no admite este pago");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * <b>409 y no 400.</b> El cuerpo era valido cuando se compuso; lo que cambio es el estado del
	 * servidor porque otro pago se llevo el saldo. Reintentar con el egreso recargado es la accion
	 * correcta, y un 400 sugeriria que el operador se equivoco.
	 */
	@ExceptionHandler(EgresoSaldoInsuficienteException.class)
	public ProblemDetail handleEgresoSaldoInsuficiente(EgresoSaldoInsuficienteException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_SALDO_INSUFICIENTE);
		problem.setTitle("El egreso ya no debe ese importe");
		problem.setProperty("egresoId", exception.getEgresoId());
		problem.setProperty("importeIntentado", exception.getImporteIntentado());
		problem.setProperty("saldoDisponible", exception.getSaldoDisponible());
		return problem;
	}

	/** Lleva el egreso existente para que la pantalla pueda llevar al operador ahi. */
	@ExceptionHandler(EgresoComprobanteDuplicadoException.class)
	public ProblemDetail handleComprobanteDuplicado(EgresoComprobanteDuplicadoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(EGRESO_COMPROBANTE_DUPLICADO);
		problem.setTitle("Ese comprobante ya esta cargado");
		problem.setProperty("egresoExistenteId", exception.getEgresoExistenteId());
		return problem;
	}

	@ExceptionHandler(PagoEgresoYaAnuladoException.class)
	public ProblemDetail handlePagoYaAnulado(PagoEgresoYaAnuladoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(PAGO_EGRESO_YA_ANULADO);
		problem.setTitle("El pago ya estaba anulado");
		return problem;
	}

	@ExceptionHandler(BeneficiarioNoVinculadoException.class)
	public ProblemDetail handleBeneficiarioNoVinculado(BeneficiarioNoVinculadoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(BENEFICIARIO_NO_VINCULADO);
		problem.setTitle("El beneficiario no tiene un vinculo vigente");
		problem.setProperty("membershipId", exception.getMembershipId());
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
