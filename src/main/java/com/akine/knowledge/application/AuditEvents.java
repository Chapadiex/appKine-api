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

	private AuditEvents() {
	}
}
