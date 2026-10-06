package com.akine.billing.domain.exception;

/**
 * Un pedido de cobro que el formato no puede atajar y el servidor si. <b>400</b>.
 *
 * <p>Hoy: un anticipo puro sin moneda. Sin deudas no hay de donde tomarla, y suponer pesos haria
 * que un anticipo en otra moneda entrara a la caja con la moneda equivocada.
 */
public class CobroInvalidoException extends RuntimeException {

	public CobroInvalidoException(String detalle) {
		super(detalle);
	}
}
