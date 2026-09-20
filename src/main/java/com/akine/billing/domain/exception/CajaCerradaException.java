package com.akine.billing.domain.exception;

/**
 * La jornada ya estaba cerrada. <b>409</b>.
 *
 * <p>RN-M20-003: una caja cerrada no se edita en silencio. <b>No se reabre nunca</b>, y un error se
 * compensa con movimientos en la jornada que este abierta hoy — no reescribiendo la que ya fue
 * arqueada, porque su {@code diferencia} es la unica evidencia de que hubo un desvio.
 *
 * <p>Es tambien el desenlace del cierre concurrente: dos cierres simultaneos, el segundo afecta
 * cero filas porque {@code estado} ya no es {@code ABIERTA}.
 */
public class CajaCerradaException extends RuntimeException {

	private final long jornadaId;

	public CajaCerradaException(long jornadaId) {
		super("La jornada de caja " + jornadaId + " ya esta cerrada y no se reabre");
		this.jornadaId = jornadaId;
	}

	public long getJornadaId() {
		return jornadaId;
	}
}
