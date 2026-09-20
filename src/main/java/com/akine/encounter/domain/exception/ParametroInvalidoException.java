package com.akine.encounter.domain.exception;

/**
 * Un parametro de tratamiento esta mal tipado. <b>400.</b>
 *
 * <p>Es el caso borde que el plan de implementacion nombra: <i>"parametro legado sin tipo/unidad
 * que debe rechazarse o normalizarse explicitamente"</i>. Se elige <b>rechazar</b>: normalizar
 * adivinando el tipo es como se cuela un valor de dosificacion clinica interpretado al reves.
 *
 * <p><b>400 y no 409</b>: no hay ningun estado del sistema que impida la operacion, falta o sobra
 * un dato del pedido. Confundirlos haria que la pantalla ofrezca "reintentar" donde lo que
 * corresponde es corregir el campo.
 *
 * <p>Viaja con la <b>clave</b> del parametro: un 400 que no dice cual de los seis parametros esta
 * mal obliga al usuario a probar de a uno.
 */
public class ParametroInvalidoException extends RuntimeException {

	private final String clave;
	private final String motivo;

	public ParametroInvalidoException(String clave, String motivo) {
		super("Parametro invalido '" + clave + "': " + motivo);
		this.clave = clave;
		this.motivo = motivo;
	}

	public String getClave() {
		return clave;
	}

	public String getMotivo() {
		return motivo;
	}
}
