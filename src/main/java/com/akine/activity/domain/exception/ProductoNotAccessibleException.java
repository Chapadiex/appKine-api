package com.akine.activity.domain.exception;

/** 404: inexistente o de otro tenant. Nunca 403: un 403 confirmaria que existe. */
public class ProductoNotAccessibleException extends RuntimeException {

	private final long productoId;

	public ProductoNotAccessibleException(long productoId) {
		super(String.format("El producto %s no existe en este alcance", productoId));
		this.productoId = productoId;
	}

	public long getProductoId() {
		return productoId;
	}
}
