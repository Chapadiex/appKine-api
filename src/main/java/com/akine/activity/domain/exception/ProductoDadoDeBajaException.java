package com.akine.activity.domain.exception;

/** 409 {@code conflict}: un producto dado de baja no se edita. La baja es definitiva. */
public class ProductoDadoDeBajaException extends RuntimeException {

	private final long productoId;

	public ProductoDadoDeBajaException(long productoId) {
		super("El producto " + productoId + " esta dado de baja y no se edita");
		this.productoId = productoId;
	}

	public long getProductoId() {
		return productoId;
	}
}
