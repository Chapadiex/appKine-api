package com.akine.billing.domain.exception;

/** El movimiento no existe, o es de otro tenant o de otra sede. <b>404</b>, por lo mismo. */
public class MovimientoCajaNotAccessibleException extends RuntimeException {

	private final long movimientoId;

	public MovimientoCajaNotAccessibleException(long movimientoId) {
		super("El movimiento de caja " + movimientoId + " no existe en este alcance");
		this.movimientoId = movimientoId;
	}

	public long getMovimientoId() {
		return movimientoId;
	}
}
