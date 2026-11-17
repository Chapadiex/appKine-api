package com.akine.platform.spi.modelo;

/**
 * No hubo lugar en el cupo del modelo dentro del plazo. Es un caso de
 * {@link ModeloNoDisponibleException}: quien no necesita distinguirlo la trata igual.
 */
public class ModeloOcupadoException extends ModeloNoDisponibleException {

	public ModeloOcupadoException() {
		super();
	}
}
