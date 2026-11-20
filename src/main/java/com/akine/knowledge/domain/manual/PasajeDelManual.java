package com.akine.knowledge.domain.manual;

import java.util.Objects;

/**
 * Un trozo citable de una ficha: una tarea, o una de las secciones fijas. El asistente cita un pasaje
 * entero y textual, por eso cada uno se entiende solo.
 *
 * @param id       {@code "<fichaId>#<slug del titulo>"}
 * @param markdown el cuerpo del pasaje, sin su titulo
 */
public record PasajeDelManual(String id, String fichaId, TipoDePasaje tipo, String titulo, String markdown) {

	public PasajeDelManual {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(fichaId, "fichaId");
		Objects.requireNonNull(tipo, "tipo");
		Objects.requireNonNull(titulo, "titulo");
		markdown = markdown == null ? "" : markdown;
	}
}
