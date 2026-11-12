package com.akine.knowledge.domain.exception;

/** El archivo subido no se acepta como libro. */
public class LibroArchivoNoAceptadoException extends RuntimeException {

	public enum Causa { NO_ES_PDF, MUY_GRANDE, VACIO }

	private final Causa causa;

	public LibroArchivoNoAceptadoException(Causa causa) {
		super("El archivo no se acepta como libro: " + causa.name() + ".");
		this.causa = causa;
	}

	public Causa getCausa() {
		return causa;
	}
}
