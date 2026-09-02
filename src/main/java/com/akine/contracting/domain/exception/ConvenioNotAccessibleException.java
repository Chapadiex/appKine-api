package com.akine.contracting.domain.exception;

/**
 * El convenio no existe, es de otra organizacion, o es de otra sede que la de la ruta. <b>Los tres
 * casos son el mismo</b>: el advice responde 404 sin distinguirlos, por el mismo motivo que
 * {@link FinanciadorNotAccessibleException} — un 403 confirmaria que ese id existe.
 *
 * <p>El tercer caso puede sorprender —el convenio existe y es del mismo tenant— y es deliberado:
 * la ruta declara a que sede pertenece (RN-M16-001) y resolverlo bajo otra seria una respuesta que
 * miente.
 */
public class ConvenioNotAccessibleException extends RuntimeException {

	private final long convenioId;

	public ConvenioNotAccessibleException(long convenioId) {
		super("Convenio no accesible: " + convenioId);
		this.convenioId = convenioId;
	}

	public long getConvenioId() {
		return convenioId;
	}
}
