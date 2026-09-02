package com.akine.contracting.domain.exception;

/**
 * El arancel no existe, es de otra organizacion, o es de otro convenio que el de la ruta. Los tres
 * responden 404 y con el mismo texto. Ver {@link ConvenioNotAccessibleException}.
 */
public class ArancelNotAccessibleException extends RuntimeException {

	private final long arancelId;

	public ArancelNotAccessibleException(long arancelId) {
		super("Arancel no accesible: " + arancelId);
		this.arancelId = arancelId;
	}

	public long getArancelId() {
		return arancelId;
	}
}
