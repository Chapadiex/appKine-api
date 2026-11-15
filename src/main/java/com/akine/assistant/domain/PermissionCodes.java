package com.akine.assistant.domain;

/** Permisos que consulta el modulo {@code assistant}. */
public final class PermissionCodes {

	/**
	 * Preguntarle al asistente de datos. Los cuatro roles de personal, por base. Es solo la puerta:
	 * lo que cada respuesta muestra lo decide el permiso de la herramienta que la produce.
	 */
	public static final String ASISTENTE_USE = "asistente:use";

	private PermissionCodes() {
	}
}
