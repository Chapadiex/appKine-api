package com.akine.resource.domain.exception;

/**
 * Se intento mutar un espacio dado de baja (409).
 *
 * <p>409 y no 404: el actor tiene el permiso y el recurso esta en su alcance; lo que no admite
 * la operacion es el ESTADO. Y no 404 porque el espacio se sigue leyendo con 200 (RN-M04-003):
 * responder que no existe justo cuando se lo quiere editar seria contradecir la lectura que
 * acaba de devolverlo.
 *
 * <p>La operacion viaja en la excepcion porque los dos casos se cuentan distinto al usuario:
 * "no se puede editar un espacio dado de baja" y "ese espacio ya estaba dado de baja" son
 * mensajes distintos, y el contrato les da dos {@code type} distintos para que el frontend no
 * tenga que leer prosa.
 */
public class EspacioInactiveException extends RuntimeException {

	/** Que se intento hacer sobre el espacio inactivo. */
	public enum Operacion {

		/** Editar nombre, tipo, capacidad, notas o vigencia. */
		EDICION,

		/** Darlo de baja por segunda vez. */
		BAJA
	}

	private final long espacioId;
	private final Operacion operacion;

	public EspacioInactiveException(long espacioId, Operacion operacion) {
		super("El espacio " + espacioId + " esta dado de baja");
		this.espacioId = espacioId;
		this.operacion = operacion;
	}

	public long getEspacioId() {
		return espacioId;
	}

	public Operacion getOperacion() {
		return operacion;
	}
}
