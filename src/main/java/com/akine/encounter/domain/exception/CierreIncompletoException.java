package com.akine.encounter.domain.exception;

/**
 * Falta algo que el cierre exige. <b>400</b>.
 *
 * <p>Es un problema del cuerpo enviado y no del estado del servidor: reintentar el mismo cuerpo
 * falla igual. Los minimos son dos y ninguno es un formalismo — ver {@code CierreDeSesion}.
 */
public class CierreIncompletoException extends RuntimeException {

	public CierreIncompletoException(String message) {
		super(message);
	}
}
