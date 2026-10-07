package com.akine.offering.domain.exception;

/** El precio particular esta dado de baja: no admite cambios ni una segunda baja (B-3). */
public class PrecioParticularInactivoException extends RuntimeException {

	private final long precioId;

	public PrecioParticularInactivoException(long precioId) {
		super("Precio particular dado de baja: " + precioId);
		this.precioId = precioId;
	}

	public long getPrecioId() {
		return precioId;
	}
}
