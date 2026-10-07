package com.akine.scheduling.domain.exception;

/**
 * La serie no existe en esa sede, o es de otro tenant. Los dos casos colapsan en 404: distinguirlos
 * confirmaria que el id existe en otra organizacion.
 */
public class SerieNotAccessibleException extends RuntimeException {

	private final long serieId;

	public SerieNotAccessibleException(long serieId) {
		super("La serie de turnos " + serieId + " no existe o no es accesible");
		this.serieId = serieId;
	}

	public long getSerieId() {
		return serieId;
	}
}
