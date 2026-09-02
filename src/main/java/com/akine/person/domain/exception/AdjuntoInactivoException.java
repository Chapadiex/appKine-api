package com.akine.person.domain.exception;

/**
 * El adjunto ya estaba dado de baja y la operacion exige uno vigente (409).
 *
 * <p>Se sigue leyendo y se sigue DESCARGANDO: el caso borde "descarga tras baja" de la etapa se
 * resuelve permitiendola. Lo que no admite un adjunto de baja es reclasificarse ni volver a darse
 * de baja.
 */
public class AdjuntoInactivoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long adjuntoId;

	public AdjuntoInactivoException(long adjuntoId) {
		super("El adjunto ya estaba dado de baja: " + adjuntoId);
		this.adjuntoId = adjuntoId;
	}

	public long getAdjuntoId() {
		return adjuntoId;
	}
}
