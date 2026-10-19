package com.akine.activity.domain.exception;

/**
 * La clase tiene honorarios liquidados vigentes: cambiar sus instructores cambiaria el reparto de
 * una liquidacion cerrada (AKINE-08.09b). 409. Se anula el egreso, que la libera.
 */
public class ClaseConHonorariosLiquidadosException extends RuntimeException {

	private final long claseId;

	public ClaseConHonorariosLiquidadosException(long claseId) {
		super("La clase " + claseId + " tiene honorarios liquidados: sus instructores no cambian");
		this.claseId = claseId;
	}

	public long getClaseId() {
		return claseId;
	}
}
