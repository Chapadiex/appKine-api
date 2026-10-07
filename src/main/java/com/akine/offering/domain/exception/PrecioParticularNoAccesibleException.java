package com.akine.offering.domain.exception;

/** El precio particular no existe en esa oferta, o es de otro tenant: 404 (B-3). */
public class PrecioParticularNoAccesibleException extends RuntimeException {

	private final long precioId;

	public PrecioParticularNoAccesibleException(long precioId) {
		super("Precio particular no accesible: " + precioId);
		this.precioId = precioId;
	}

	public long getPrecioId() {
		return precioId;
	}
}
