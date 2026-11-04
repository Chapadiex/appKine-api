package com.akine.scheduling.application;

/**
 * El lector solo alcanza su propia agenda ({@code ACTIVIDAD_PROPIA}) y pidio la de otro profesional.
 * Es un 403 {@code forbidden} como cualquier otro, con la extension {@code alcance = "PROPIO"} para que la
 * pantalla pueda decir "solo podes ver tu agenda" en vez de "sin permiso".
 */
public class AgendaAjenaException extends RuntimeException {

	public AgendaAjenaException() {
		super("Con este perfil solo se ve el historial de la propia agenda");
	}
}
