package com.akine.activity.domain.exception;

/**
 * 409 {@code pase-no-ajustable}: el estado del pase no admite el ajuste o la anulacion pedidos.
 * Lleva {@code motivo}: anulado, vencido, saldo final fuera de rango (RN-M29-008: nunca negativo, y
 * nunca por encima de lo que se compro) o, en la anulacion, creditos ya usados.
 */
public class PaseNoAjustableException extends RuntimeException {

	private final long paseId;
	private final String motivo;

	public PaseNoAjustableException(long paseId, String motivo) {
		super("El pase " + paseId + " no admite la operacion: " + motivo);
		this.paseId = paseId;
		this.motivo = motivo;
	}

	public long getPaseId() {
		return paseId;
	}

	public String getMotivo() {
		return motivo;
	}
}
