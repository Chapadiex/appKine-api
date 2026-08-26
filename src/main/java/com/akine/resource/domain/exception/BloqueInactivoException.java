package com.akine.resource.domain.exception;

/**
 * Se intento mutar un bloque de disponibilidad dado de baja (409).
 *
 * <p>409 y no 404: el actor tiene el permiso, el bloque esta en su alcance y el servicio lo
 * <b>encontro</b> — la carga interna lo devuelve, activo o no. Lo que no admite la operacion es
 * el ESTADO, y ese es exactamente el caso de un 409.
 *
 * <p>Un 404 diria "no existe" sobre una fila que el sistema conserva a proposito (RN-M05-003) y
 * que el propio mensaje de error tiene que poder explicar: "ese bloque ya estaba dado de baja" es
 * informacion distinta de "ese bloque no existe", y la primera es la unica que le sirve al
 * administrador que acaba de apretar el boton dos veces. Ademas dejaria a
 * {@code BloqueNotAccessibleException} significando dos cosas incompatibles a la vez.
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
