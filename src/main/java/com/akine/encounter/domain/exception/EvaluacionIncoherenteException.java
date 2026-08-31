package com.akine.encounter.domain.exception;

/**
 * La evaluacion tiene un dato que seria falso. <b>400</b>, no 409.
 *
 * <p>Es un problema del CUERPO enviado, no del estado del servidor: un dolor de 12 en una escala de
 * 0 a 10 no depende de nada que pueda cambiar entre dos peticiones. Un 409 sugeriria reintentar, y
 * reintentar no lo arregla.
 */
public class EvaluacionIncoherenteException extends RuntimeException {

	public EvaluacionIncoherenteException(String message) {
		super(message);
	}
}
