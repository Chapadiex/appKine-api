package com.akine.encounter.domain.exception;

/** El espacio no existe, o es de otro tenant o de otra sede. <b>404 siempre.</b> */
public class EspacioNoAccesibleException extends RuntimeException {

	private final long espacioId;

	public EspacioNoAccesibleException(long espacioId) {
		super("Espacio no accesible: " + espacioId);
		this.espacioId = espacioId;
	}

	public long getEspacioId() {
		return espacioId;
	}
}
