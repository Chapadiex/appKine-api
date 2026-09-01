package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Lo imputado no da el total del cobro. <b>400</b>.
 *
 * <p>Mientras no existan los anticipos —AKINE-07.03, porque sin Caja un anticipo es plata que entro
 * y que ningun arqueo puede encontrar— todo el dinero recibido tiene que aplicarse a alguna deuda.
 * Un cobro que imputa de menos deja plata sin destino; uno que imputa de mas cobraria deuda que no
 * se pago.
 */
public class ImputacionesNoSumanException extends RuntimeException {

	private final BigDecimal sumaImputada;
	private final BigDecimal total;

	public ImputacionesNoSumanException(BigDecimal sumaImputada, BigDecimal total) {
		super("Las imputaciones suman " + sumaImputada + " y el cobro es de " + total
				+ ". El anticipo llega en AKINE-07.03, junto con la Caja");
		this.sumaImputada = sumaImputada;
		this.total = total;
	}

	public BigDecimal getSumaImputada() {
		return sumaImputada;
	}

	public BigDecimal getTotal() {
		return total;
	}
}
