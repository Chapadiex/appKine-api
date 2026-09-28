package com.akine.encounter.domain.exception;

/**
 * Se intento registrar una medicion con una definicion dada de baja (<b>409</b>).
 *
 * <p><b>La baja del catalogo no cascadea.</b> Las mediciones que ya usaban esa definicion siguen
 * legibles y siguen entrando en la comparacion; lo unico que se impide es registrar NUEVAS. Es
 * exactamente la regla que 02.06 fijo para la baja de un servicio global, y la que hace que dar
 * de baja un test discontinuado no borre el examen de hace seis meses.
 *
 * <p>409 y no 404: la definicion existe, se sigue leyendo, y la accion que corresponde ofrecer es
 * elegir otro test — no recargar una lista que no cambio.
 */
public class MedicionDefinicionInactivaException extends RuntimeException {

	private final long definicionId;

	public MedicionDefinicionInactivaException(long definicionId) {
		super("La definicion de medicion " + definicionId + " esta dada de baja y no admite "
				+ "mediciones nuevas");
		this.definicionId = definicionId;
	}

	public long getDefinicionId() {
		return definicionId;
	}
}
