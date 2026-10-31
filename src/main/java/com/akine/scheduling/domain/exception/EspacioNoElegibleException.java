package com.akine.scheduling.domain.exception;

/** El box elegido a mano no esta habilitado para la oferta, no esta en servicio o es de otra sede. */
public class EspacioNoElegibleException extends RuntimeException {

	private final long espacioId;

	public EspacioNoElegibleException(long espacioId) {
		super("Ese box no se puede usar para esta prestacion en ese horario.");
		this.espacioId = espacioId;
	}

	public long getEspacioId() {
		return espacioId;
	}
}
