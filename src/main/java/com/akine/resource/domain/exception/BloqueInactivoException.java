package com.akine.resource.domain.exception;

/**
 * Se intento mutar un bloque de disponibilidad dado de baja (409).
 *
 * <p>409 y no 404: el actor tiene el permiso y el bloque esta en su alcance; lo que no admite la
 * operacion es el ESTADO. Y no 404 porque el bloque se sigue leyendo (RN-M05-003, la historia se
 * conserva): responder que no existe justo cuando se lo quiere editar contradiria la lectura que
 * acaba de devolverlo.
 *
 * <p>La operacion viaja en la excepcion por el mismo motivo que en
 * {@code EspacioInactiveException}: "no se puede editar un bloque dado de baja" y "ese bloque ya
 * estaba dado de baja" son dos mensajes distintos, y el contrato les da dos {@code type}
 * distintos para que el frontend no tenga que leer prosa.
 *
 * <p><b>Nota para la tarea 10:</b> esta excepcion NO figura en la lista de archivos del brief de
 * la tarea 7 y se agrego porque hacia falta — sin ella, editar o volver a dar de baja un bloque
 * inactivo no tenia forma de responder 409 sin lanzar un {@code IllegalStateException} que el
 * advice traduciria a 500. El advice del modulo tiene que mapearla.
 */
public class BloqueInactivoException extends RuntimeException {

	/** Que se intento hacer sobre el bloque inactivo. */
	public enum Operacion {

		/** Editar dia, horas o vigencia. */
		EDICION,

		/** Darlo de baja por segunda vez. */
		BAJA
	}

	private final long bloqueId;
	private final Operacion operacion;

	public BloqueInactivoException(long bloqueId, Operacion operacion) {
		super("El bloque de disponibilidad " + bloqueId + " esta dado de baja");
		this.bloqueId = bloqueId;
		this.operacion = operacion;
	}

	public long getBloqueId() {
		return bloqueId;
	}

	public Operacion getOperacion() {
		return operacion;
	}
}
