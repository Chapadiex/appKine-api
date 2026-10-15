package com.akine.activity.domain.exception;

import java.time.LocalDate;

/**
 * 409 {@code abono-periodo-superpuesto}: la persona ya tiene ese abono —no anulado— en un periodo
 * que se superpone con el pedido. Un periodo genera como maximo una obligacion (plan 08.08), y dos
 * abonos del mismo producto el mismo dia serian dos deudas por la misma cobertura.
 */
public class AbonoPeriodoSuperpuestoException extends RuntimeException {

	private final long productoId;
	private final long personaId;

	public AbonoPeriodoSuperpuestoException(
			long productoId, long personaId, LocalDate desde, LocalDate hasta) {
		super("La persona " + personaId + " ya tiene el abono " + productoId
				+ " en un periodo que se superpone con " + desde + " a " + hasta);
		this.productoId = productoId;
		this.personaId = personaId;
	}

	public long getProductoId() {
		return productoId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
