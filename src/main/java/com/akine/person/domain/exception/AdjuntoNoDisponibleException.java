package com.akine.person.domain.exception;

/**
 * La metadata del adjunto existe pero el almacenamiento no tiene su contenido (409, no 404).
 *
 * <p>La fila esta, se lista y su historico resuelve: lo que falta es el binario. Un 404 le diria
 * al operador que el documento no existe y lo empujaria a subirlo de nuevo bajo una ficha que
 * todavia afirma tenerlo.
 */
public class AdjuntoNoDisponibleException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long adjuntoId;

	public AdjuntoNoDisponibleException(long adjuntoId) {
		super("El contenido del adjunto no esta disponible: " + adjuntoId);
		this.adjuntoId = adjuntoId;
	}

	public long getAdjuntoId() {
		return adjuntoId;
	}
}
