package com.akine.clinical.domain.exception;

/**
 * La metadata del adjunto clinico existe pero el almacenamiento no tiene su contenido (409, no
 * 404).
 *
 * <p>La fila esta, se lista y su historico resuelve: lo que falta es el binario. Un 404 le diria
 * al profesional que el estudio no existe y lo empujaria a pedirselo de nuevo al paciente bajo
 * una historia que todavia afirma tenerlo.
 */
public class AdjuntoClinicoNoDisponibleException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long adjuntoId;

	public AdjuntoClinicoNoDisponibleException(long adjuntoId) {
		super("El contenido del adjunto clinico no esta disponible: " + adjuntoId);
		this.adjuntoId = adjuntoId;
	}

	public long getAdjuntoId() {
		return adjuntoId;
	}
}
