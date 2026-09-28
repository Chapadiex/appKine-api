package com.akine.resource.domain.exception;

/**
 * La definicion de medicion esta dada de baja y la operacion exige una vigente (<b>409</b>).
 *
 * <p>409 y no 404: el actor tiene el permiso y la definicion esta en su alcance —se sigue
 * leyendo con 200 y sus mediciones siguen siendo legibles y comparables—; lo que no admite la
 * operacion es el ESTADO.
 *
 * <p><b>La baja no cascadea.</b> Lo unico que se impide es editarla, volver a darla de baja o
 * registrar mediciones NUEVAS con ella. Es exactamente la regla que 02.06 dejo fijada para la
 * baja de un servicio global.
 */
public class MedicionDefinicionInactivaException extends RuntimeException {

	private final long definicionId;

	public MedicionDefinicionInactivaException(long definicionId) {
		super("La definicion de medicion " + definicionId + " esta dada de baja");
		this.definicionId = definicionId;
	}

	public long getDefinicionId() {
		return definicionId;
	}
}
