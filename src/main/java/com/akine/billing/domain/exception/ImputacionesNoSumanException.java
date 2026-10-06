package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Lo imputado mas el anticipo declarado no da el total del cobro. <b>400</b>.
 *
 * <p>Todo el dinero recibido tiene que tener destino: una deuda o un saldo a favor <b>declarado</b>
 * (F-3). Un cobro que imputa de menos sin declarar el resto deja plata sin destino; uno que imputa
 * de mas cobraria deuda que no se pago. El anticipo no se infiere del sobrante a proposito: un cero
 * de menos en una imputacion se convertiria en un saldo a favor que nadie pidio.
 */
public class ImputacionesNoSumanException extends RuntimeException {

	private final BigDecimal sumaImputada;
	private final BigDecimal anticipo;
	private final BigDecimal total;

	public ImputacionesNoSumanException(BigDecimal sumaImputada, BigDecimal total) {
		this(sumaImputada, BigDecimal.ZERO, total);
	}

	public ImputacionesNoSumanException(BigDecimal sumaImputada, BigDecimal anticipo, BigDecimal total) {
		super("Las imputaciones suman " + sumaImputada + ", el anticipo declarado es " + anticipo
				+ " y el cobro es de " + total);
		this.sumaImputada = sumaImputada;
		this.anticipo = anticipo;
		this.total = total;
	}

	public BigDecimal getSumaImputada() {
		return sumaImputada;
	}

	public BigDecimal getAnticipo() {
		return anticipo;
	}

	/** Lo que el cobro tiene destinado: imputaciones mas anticipo. Es lo que se compara con el total. */
	public BigDecimal getRecibido() {
		return sumaImputada.add(anticipo);
	}

	public BigDecimal getTotal() {
		return total;
	}
}
