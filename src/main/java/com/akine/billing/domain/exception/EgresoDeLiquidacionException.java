package com.akine.billing.domain.exception;

/**
 * Se intenta cambiar importe, categoria o periodo de un egreso que nacio de una liquidacion de
 * honorarios vigente (AKINE-08.09b). <b>409</b>.
 *
 * <p>El total del egreso es la suma exacta del detalle por clase; editarlo dejaria una
 * liquidacion que no explica lo que se paga. Concepto y comprobante si se editan.
 */
public class EgresoDeLiquidacionException extends RuntimeException {

	private final long egresoId;
	private final long liquidacionId;

	public EgresoDeLiquidacionException(long egresoId, long liquidacionId) {
		super("El egreso " + egresoId + " nacio de la liquidacion de honorarios " + liquidacionId
				+ ": su importe, categoria y periodo no se editan");
		this.egresoId = egresoId;
		this.liquidacionId = liquidacionId;
	}

	public long getEgresoId() {
		return egresoId;
	}

	public long getLiquidacionId() {
		return liquidacionId;
	}
}
