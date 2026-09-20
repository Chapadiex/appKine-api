package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Se intenta anular un egreso que ya tiene pagos vigentes. <b>409</b>.
 *
 * <p>Anular el compromiso dejaria plata fuera del cajon sin ningun compromiso que la justifique, y
 * el arqueo dejaria de reconciliar. Lo que corresponde es anular primero los pagos —que es lo que
 * la devuelve— y despues el compromiso. Mismo criterio que {@code ObligacionConCobrosException}.
 *
 * <p>Lo ya pagado viaja para que la pantalla pueda nombrar la accion correcta en vez de dejar al
 * administrativo adivinando por que no lo deja.
 */
public class EgresoConPagosException extends RuntimeException {

	private final Long egresoId;
	private final BigDecimal yaPagado;

	public EgresoConPagosException(Long egresoId, BigDecimal yaPagado) {
		super("El egreso " + egresoId + " ya tiene " + yaPagado
				+ " pagado: anularlo exige anular antes esos pagos");
		this.egresoId = egresoId;
		this.yaPagado = yaPagado;
	}

	public Long getEgresoId() {
		return egresoId;
	}

	public BigDecimal getYaPagado() {
		return yaPagado;
	}
}
