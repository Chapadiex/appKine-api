package com.akine.contracting.domain.exception;

/**
 * Se intento crear un PLAN bajo un financiador dado de baja.
 *
 * <p>Es el 409 que hace visible que la baja de un financiador <b>no cascadea</b>: sus planes
 * existentes siguen ahi y sus coberturas historicas resuelven, y lo unico que se impide es
 * agregar planes nuevos. Mismo tratamiento que {@code ServicioInactivoException} en M27.
 */
public class FinanciadorInactivoException extends RuntimeException {

	private final long financiadorId;

	public FinanciadorInactivoException(long financiadorId) {
		super("Financiador dado de baja: " + financiadorId);
		this.financiadorId = financiadorId;
	}

	public long getFinanciadorId() {
		return financiadorId;
	}
}
