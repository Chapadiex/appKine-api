package com.akine.billing.domain.exception;

/** Se intenta confirmar algo que ya no es un borrador. <b>409</b>. */
public class EgresoNoConfirmableException extends RuntimeException {

	private final Long egresoId;
	private final String estado;

	public EgresoNoConfirmableException(Long egresoId, String estado) {
		super("El egreso " + egresoId + " esta " + estado + " y no se puede confirmar");
		this.egresoId = egresoId;
		this.estado = estado;
	}

	public Long getEgresoId() {
		return egresoId;
	}

	public String getEstado() {
		return estado;
	}
}
