package com.akine.knowledge.domain.exception;

/** La cola de consultas a la biblioteca esta llena: hay que reintentar mas tarde. */
public class ConsultaOcupadaException extends RuntimeException {

	/** Segundos que se sugieren esperar antes de reintentar ({@code Retry-After}). */
	public static final int REINTENTAR_EN_SEGUNDOS = 15;

	public ConsultaOcupadaException() {
		super("El asistente esta ocupado. Reintenta en unos segundos.");
	}
}
