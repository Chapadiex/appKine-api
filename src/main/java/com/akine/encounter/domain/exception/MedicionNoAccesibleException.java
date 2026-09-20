package com.akine.encounter.domain.exception;

/**
 * No hay una medicion de esa medida y ese lado en esa sesion (<b>404</b>).
 *
 * <p>La emite unicamente el borrado: pedir que se borre algo que no esta es un 404, no un exito
 * silencioso. Un {@code DELETE} que responde 204 sobre una fila inexistente le oculta a la
 * pantalla que estaba mirando datos viejos, y el profesional se queda creyendo que borro una
 * medicion que en realidad sigue ahi bajo otro lado.
 *
 * <p>Lleva la lateralidad porque la identidad de una medicion incluye el lado: "no existe la
 * medicion X" seria falso cuando existe la del otro lado.
 */
public class MedicionNoAccesibleException extends RuntimeException {

	private final long definicionId;
	private final String lateralidad;

	public MedicionNoAccesibleException(long definicionId, String lateralidad) {
		super("No hay una medicion de la definicion " + definicionId + " con lateralidad "
				+ lateralidad + " en esa sesion");
		this.definicionId = definicionId;
		this.lateralidad = lateralidad;
	}

	public long getDefinicionId() {
		return definicionId;
	}

	public String getLateralidad() {
		return lateralidad;
	}
}
