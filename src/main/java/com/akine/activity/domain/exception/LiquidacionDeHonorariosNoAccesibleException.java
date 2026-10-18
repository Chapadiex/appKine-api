package com.akine.activity.domain.exception;

/** La liquidacion de honorarios no existe en la sede, o es de otro tenant (AKINE-08.09b). 404. */
public class LiquidacionDeHonorariosNoAccesibleException extends RuntimeException {

	private final long liquidacionId;

	public LiquidacionDeHonorariosNoAccesibleException(long liquidacionId) {
		super("Liquidacion de honorarios no accesible: " + liquidacionId);
		this.liquidacionId = liquidacionId;
	}

	public long getLiquidacionId() {
		return liquidacionId;
	}
}
