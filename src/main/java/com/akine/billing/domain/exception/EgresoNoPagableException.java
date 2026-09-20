package com.akine.billing.domain.exception;

/**
 * El egreso no admite este pago por su estado. <b>409</b>.
 *
 * <p>Un borrador no se paga —todavia se esta armando—, un anulado tampoco, y uno ya saldado no
 * debe nada. <b>Solo un egreso confirmado puede mover la caja</b>: es como esta etapa lee
 * RN-M22-001.
 */
public class EgresoNoPagableException extends RuntimeException {

	private final Long egresoId;
	private final String motivo;

	public EgresoNoPagableException(Long egresoId, String motivo) {
		super("El egreso " + egresoId + " no admite el pago: " + motivo);
		this.egresoId = egresoId;
		this.motivo = motivo;
	}

	public Long getEgresoId() {
		return egresoId;
	}

	public String getMotivo() {
		return motivo;
	}
}
