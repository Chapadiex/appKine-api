package com.akine.clinical.domain.exception;

/** El archivo que se quiere declarar ya esta en la historia, cargado por un profesional. El mensaje no nombra al existente (DP-47, 409). */
public class AdjuntoYaEnHistoriaException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public AdjuntoYaEnHistoriaException(String mensaje) {
		super(mensaje);
	}
}
