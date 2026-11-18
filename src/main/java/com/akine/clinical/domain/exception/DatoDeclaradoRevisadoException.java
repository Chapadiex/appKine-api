package com.akine.clinical.domain.exception;

/** El dato declarado ya fue revisado: solo lo da de baja un profesional desde la historia (DP-47, 409). */
public class DatoDeclaradoRevisadoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public DatoDeclaradoRevisadoException(String mensaje) {
		super(mensaje);
	}
}
