package com.akine.clinical.domain.exception;

/**
 * El archivo no pasa la validacion de tipo o de tamano (RN-M25-001), y por lo tanto es 400.
 *
 * <p><b>Un solo tipo para las dos familias</b>, con {@code motivo} como propiedad extra: para la
 * pantalla el desenlace es el mismo —decir por que ese archivo no entra y pedir otro— y publicar
 * dos {@code type} obligaria al cliente a manejar dos codigos para una misma accion imposible.
 *
 * <p>400 y no 415: el tipo declarado del request es correcto —es {@code multipart/form-data}— y
 * lo que se rechaza es el CONTENIDO de una de sus partes, que es un dato invalido y no un formato
 * de peticion que el servidor no sepa leer.
 *
 * <p>Es una clase propia y no la de {@code person} por el motivo de siempre: modulos distintos, y
 * ArchUnit rechaza importarla.
 */
public class ArchivoClinicoNoAceptadoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String motivo;

	public ArchivoClinicoNoAceptadoException(String motivo, String detalle) {
		super(detalle);
		this.motivo = motivo;
	}

	/** {@code TIPO_NO_PERMITIDO} o {@code DEMASIADO_GRANDE}. */
	public String getMotivo() {
		return motivo;
	}
}
