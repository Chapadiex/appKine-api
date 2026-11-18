package com.akine.encounter.domain.exception;

/**
 * Se lleno el cupo del modelo de sugerencias en esta instancia. <b>503 {@code modelo-ocupado}</b>:
 * un caso de {@link ModeloDeEvolucionNoDisponibleException} que el cliente puede reintentar enseguida.
 */
public class ModeloDeEvolucionOcupadoException extends ModeloDeEvolucionNoDisponibleException {

	public ModeloDeEvolucionOcupadoException(Throwable causa) {
		super(causa);
	}
}
