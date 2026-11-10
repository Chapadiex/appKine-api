package com.akine.knowledge.application;

/** Tipos de evento de auditoria que emite {@code knowledge} (M30). Sin contenido del libro. */
final class AuditEvents {

	static final String LIBRO_SUBIDO = "LIBRO_SUBIDO";
	static final String LIBRO_EDITADO = "LIBRO_EDITADO";
	static final String LIBRO_REEMPLAZADO = "LIBRO_REEMPLAZADO";
	static final String LIBRO_REPROCESADO = "LIBRO_REPROCESADO";
	static final String LIBRO_ACTIVADO = "LIBRO_ACTIVADO";
	static final String LIBRO_DESACTIVADO = "LIBRO_DESACTIVADO";
	static final String LIBRO_ELIMINADO = "LIBRO_ELIMINADO";
	/** El worker dejo el libro LISTO (actor: el sistema). */
	static final String LIBRO_PROCESADO = "LIBRO_PROCESADO";
	/** El worker dejo el libro en ERROR (actor: el sistema). */
	static final String LIBRO_CON_ERROR = "LIBRO_CON_ERROR";
	/** Un reemplazo quedo LISTO y el original se desactivo (actor: el sistema). */
	static final String LIBRO_REEMPLAZO_APLICADO = "LIBRO_REEMPLAZO_APLICADO";

	private AuditEvents() {
	}
}
