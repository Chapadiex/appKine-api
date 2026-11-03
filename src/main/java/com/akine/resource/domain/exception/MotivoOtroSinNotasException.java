package com.akine.resource.domain.exception;

/**
 * Una excepcion de disponibilidad con motivo {@code OTRO} y sin notas (checklist de agenda §13.2).
 * <b>400 {@code validation-error}</b>, con el error en el campo {@code notes}: "Otro" sin explicacion
 * es un motivo que la auditoria no puede leer seis meses despues.
 */
public class MotivoOtroSinNotasException extends RuntimeException {

	public static final String CAMPO = "notes";

	public MotivoOtroSinNotasException() {
		super("Con el motivo Otro hay que escribir las notas");
	}
}
