package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Se intenta imputar o reintegrar mas saldo a favor del que el cobro tiene. <b>409</b>.
 *
 * <p>409 y no 400 por lo mismo que {@link SaldoInsuficienteException}: el desenlace tipico es una
 * carrera —otro operador uso el anticipo entre que la pantalla lo mostro y este confirmo— y la
 * accion correcta es recargar. Lleva lo disponible para que la pantalla lo muestre.
 */
public class SaldoAFavorInsuficienteException extends RuntimeException {

	private final Long cobroId;
	private final BigDecimal importeIntentado;
	private final BigDecimal disponible;

	public SaldoAFavorInsuficienteException(
			Long cobroId, BigDecimal importeIntentado, BigDecimal disponible) {

		super("El cobro " + cobroId + " tiene " + disponible + " a favor y se intento usar "
				+ importeIntentado);
		this.cobroId = cobroId;
		this.importeIntentado = importeIntentado;
		this.disponible = disponible;
	}

	public Long getCobroId() {
		return cobroId;
	}

	public BigDecimal getImporteIntentado() {
		return importeIntentado;
	}

	public BigDecimal getDisponible() {
		return disponible;
	}
}
