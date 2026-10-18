package com.akine.activity.domain.exception;

/**
 * 409 {@code producto-no-vendible}: el producto esta dado de baja o fuera de su ventana
 * comercial. Es el caso borde "producto inactivo durante el checkout": se revalida dentro de la
 * transaccion de la venta, no al armar la pantalla.
 */
public class ProductoNoVendibleException extends RuntimeException {

	private final long productoId;
	private final String motivo;

	public ProductoNoVendibleException(long productoId, String motivo) {
		super("El producto " + productoId + " no se puede vender: " + motivo);
		this.productoId = productoId;
		this.motivo = motivo;
	}

	public long getProductoId() {
		return productoId;
	}

	public String getMotivo() {
		return motivo;
	}
}
