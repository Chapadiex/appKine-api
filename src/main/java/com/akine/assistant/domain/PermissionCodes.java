package com.akine.assistant.domain;

/** Permisos que consulta el modulo {@code assistant}. */
public final class PermissionCodes {

	/**
	 * Preguntarle al asistente de datos. Los cuatro roles de personal, por base. Es solo la puerta:
	 * lo que cada respuesta muestra lo decide el permiso de la herramienta que la produce.
	 */
	public static final String ASISTENTE_USE = "asistente:use";

	/**
	 * Consultas libres: si la pregunta no entra en ninguna intencion, el modelo escribe un SELECT
	 * sobre las vistas de {@code asistente_sql}. Solo los dos roles de administracion, por base.
	 */
	public static final String ASISTENTE_SQL = "asistente:sql";

	/**
	 * Dictar por voz en los campos de texto (docs/diseno/AKINE-dictado-por-voz.md). Los cuatro roles
	 * de personal, por base.
	 */
	public static final String DICTADO_USE = "dictado:use";

	private PermissionCodes() {
	}
}
