package com.akine.resource.domain.exception;

/**
 * Se intento dar de baja una excepcion de disponibilidad que ya estaba dada de baja (409).
 *
 * <p>409 y no 404, con el mismo criterio que {@link BloqueInactivoException}: el actor tiene el
 * permiso y la fila esta en su alcance; lo que no admite la operacion es el ESTADO. Y no 404
 * porque la excepcion se sigue leyendo —la baja es logica y la historia se conserva—, asi que
 * responder que no existe justo cuando se la quiere dar de baja contradiria la lectura que acaba
 * de devolverla.
 *
 * <p><b>Nota para la tarea 10:</b> tampoco figura en el brief; ver
 * {@link ExcepcionNotAccessibleException}. El advice del modulo tiene que mapearla a 409.
 */
public class ExcepcionInactivaException extends RuntimeException {

	private final long excepcionId;

	public ExcepcionInactivaException(long excepcionId) {
		super("La excepcion de disponibilidad " + excepcionId + " ya esta dada de baja");
		this.excepcionId = excepcionId;
	}

	public long getExcepcionId() {
		return excepcionId;
	}
}
