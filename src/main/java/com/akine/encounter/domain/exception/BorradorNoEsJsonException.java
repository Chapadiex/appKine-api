package com.akine.encounter.domain.exception;

/**
 * El {@code contenido} del borrador no es un documento JSON que MySQL acepte. <b>400</b>, no 409.
 *
 * <p>{@code sesion.borrador} es una columna {@code JSON}: un texto que no lo es moria en el
 * {@code INSERT}/{@code UPDATE} con una violacion de integridad, y el handler global la traduce
 * a 409 "choca con un dato ya existente", que manda al cliente a recargar y reintentar algo que
 * va a fallar igual. Es un problema del cuerpo enviado, y se rechaza antes de escribir.
 */
public class BorradorNoEsJsonException extends RuntimeException {

	/** El campo del pedido que trae el problema, para el {@code errors} del Problem Detail. */
	public static final String CAMPO = "contenido";

	public BorradorNoEsJsonException(String message) {
		super(message);
	}
}
