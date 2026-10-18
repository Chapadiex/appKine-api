package com.akine.activity.domain.exception;

/** 409 {@code producto-nombre-en-uso}: ya hay un producto vigente con ese nombre en la sede. */
public class ProductoNombreEnUsoException extends RuntimeException {

	private final String nombre;

	public ProductoNombreEnUsoException(String nombre) {
		super("Ya existe un producto vigente llamado '" + nombre + "' en esta sede");
		this.nombre = nombre;
	}

	public String getNombre() {
		return nombre;
	}
}
