package com.akine.clinical.domain.exception;

/**
 * Lo que se quiere declarar ya esta en la historia, cargado por un profesional. Quien declara no
 * lee la historia: el error no lleva datos del existente (mismo criterio que
 * {@link AdjuntoYaEnHistoriaException}).
 */
public class DatoYaEnHistoriaException extends RuntimeException {

	public DatoYaEnHistoriaException() {
		super("Ese dato ya esta registrado en la historia clinica");
	}
}
