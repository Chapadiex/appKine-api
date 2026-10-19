package com.akine.offering.domain.exception;

/** El honorario por clase no existe en esa oferta, o es de otro tenant: 404 (08.09b). */
public class HonorarioDeClaseNoAccesibleException extends RuntimeException {

	private final long honorarioId;

	public HonorarioDeClaseNoAccesibleException(long honorarioId) {
		super("Honorario por clase no accesible: " + honorarioId);
		this.honorarioId = honorarioId;
	}

	public long getHonorarioId() {
		return honorarioId;
	}
}
