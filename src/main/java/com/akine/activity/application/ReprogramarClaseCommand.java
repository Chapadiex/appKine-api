package com.akine.activity.application;

import java.time.Instant;

/**
 * Lo que hay que decir para reprogramar una clase (RF-M28-005).
 *
 * <p>Lleva la {@code version} leida porque reprogramar es una escritura sobre estado compartido:
 * sin ella, dos operadores que abrieron la misma clase se pisan y el ultimo gana en silencio.
 *
 * @param espacioId box elegido. {@code null} deja que el servidor elija el primero apto y libre,
 *                  que <b>puede no ser el actual</b>: para conservarlo hay que mandarlo
 */
public record ReprogramarClaseCommand(
		Instant inicio,
		Instant fin,
		Long profesionalId,
		int capacidad,
		long version,
		Long espacioId) {

	/** AKINE-09.02: ver {@link FechasAdmitidas}. */
	public ReprogramarClaseCommand {
		FechasAdmitidas.exigir(inicio, "inicio");
		FechasAdmitidas.exigir(fin, "fin");
	}
}
