package com.akine.knowledge.domain.manual;

/** La ficha no existe o la persona no la puede ver: los dos casos responden igual, 404. */
public class FichaNoEncontradaException extends RuntimeException {

	public FichaNoEncontradaException(String id) {
		super("No hay una ficha del manual '" + id + "' para mostrar.");
	}
}
