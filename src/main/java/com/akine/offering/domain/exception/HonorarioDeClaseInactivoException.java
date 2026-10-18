package com.akine.offering.domain.exception;

/** El honorario por clase esta dado de baja: no admite cambios ni una segunda baja (08.09b). */
public class HonorarioDeClaseInactivoException extends RuntimeException {

	private final long honorarioId;

	public HonorarioDeClaseInactivoException(long honorarioId) {
		super("Honorario por clase dado de baja: " + honorarioId);
		this.honorarioId = honorarioId;
	}

	public long getHonorarioId() {
		return honorarioId;
	}
}
