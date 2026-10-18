package com.akine.activity.domain.exception;

import java.math.BigDecimal;
import java.util.List;

/**
 * El borrador de honorarios cambio entre que se miro y se confirmo (AKINE-08.09b). 409: no se
 * confirma algo distinto de lo que el administrador vio. Lleva lo actual para que la pantalla lo
 * muestre sin otra ida y vuelta.
 */
public class BorradorDeHonorariosDesactualizadoException extends RuntimeException {

	private final BigDecimal importeTotalActual;
	private final List<Long> claseIdsActuales;

	public BorradorDeHonorariosDesactualizadoException(BigDecimal importeTotalActual, List<Long> claseIdsActuales) {
		super("El borrador de honorarios cambio desde que se calculo: total actual " + importeTotalActual
				+ ", clases " + claseIdsActuales);
		this.importeTotalActual = importeTotalActual;
		this.claseIdsActuales = List.copyOf(claseIdsActuales);
	}

	public BigDecimal getImporteTotalActual() {
		return importeTotalActual;
	}

	public List<Long> getClaseIdsActuales() {
		return claseIdsActuales;
	}
}
