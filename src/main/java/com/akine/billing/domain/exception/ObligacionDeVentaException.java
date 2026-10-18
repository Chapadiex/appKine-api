package com.akine.billing.domain.exception;

/**
 * Se intenta anular desde la cuenta corriente la deuda de la venta de un pase o de un abono.
 * <b>409</b> (AKINE-08.09, RF-M18-006).
 *
 * <p>Esa deuda es la mitad de un acto comercial: la otra mitad es el pase, con sus creditos. Anular
 * solo la deuda deja el pase vivo y consumible sin que nadie lo pague —un pack regalado sin rastro
 * de que fue un regalo—, y RF-M18-006 pide que los datos derivados queden consistentes con la
 * entidad principal. La anulacion de una venta se hace sobre el pase
 * ({@code POST /pases/{paseId}/anulacion}), que anula pase y deuda en la misma transaccion.
 *
 * <p>Lleva {@code paseId} para que la pantalla ofrezca la accion correcta.
 */
public class ObligacionDeVentaException extends RuntimeException {

	private final long obligacionId;
	private final Long paseId;
	private final String origen;

	public ObligacionDeVentaException(long obligacionId, Long paseId, String origen) {
		super("La obligacion " + obligacionId + " es la deuda de una venta (" + origen
				+ "): se anula anulando el pase " + paseId);
		this.obligacionId = obligacionId;
		this.paseId = paseId;
		this.origen = origen;
	}

	public long getObligacionId() {
		return obligacionId;
	}

	public Long getPaseId() {
		return paseId;
	}

	public String getOrigen() {
		return origen;
	}
}
