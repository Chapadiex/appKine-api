package com.akine.billing.domain.exception;

/** Ese item no existe, o no pertenece a esa presentacion. <b>404</b>. */
public class PresentacionItemNotAccessibleException extends RuntimeException {

	private final long itemId;

	public PresentacionItemNotAccessibleException(long itemId) {
		super("El item de presentacion " + itemId + " no existe o no es accesible");
		this.itemId = itemId;
	}

	public long getItemId() {
		return itemId;
	}
}
