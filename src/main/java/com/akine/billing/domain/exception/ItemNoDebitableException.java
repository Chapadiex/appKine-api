package com.akine.billing.domain.exception;

/**
 * Ese item ya no admite un debito. <b>409</b>.
 *
 * <p>Ya fue debitado, ya fue aceptado al conciliar, o pertenece a un borrador anulado. Un segundo
 * debito sobre la misma fila restaria dos veces del saldo del lote por una sola prestacion
 * rechazada.
 */
public class ItemNoDebitableException extends RuntimeException {

	private final Long itemId;
	private final String estado;

	public ItemNoDebitableException(Long itemId, String estado) {
		super("El item " + itemId + " no admite un debito: esta " + estado);
		this.itemId = itemId;
		this.estado = estado;
	}

	public Long getItemId() {
		return itemId;
	}

	public String getEstado() {
		return estado;
	}
}
