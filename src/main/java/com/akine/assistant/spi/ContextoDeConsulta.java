package com.akine.assistant.spi;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Contexto de la consulta: el dia de hoy en la zona de la sede, la pantalla donde esta la persona y la
 * pregunta tal como la escribio.
 *
 * @param pregunta la pregunta original del usuario, o {@code null}. Sirve a las herramientas que necesitan
 *                 lo que el usuario dijo y no solo el fragmento que el modelo les paso (por ejemplo, el
 *                 titulo completo de una clase que el modelo acorto)
 */
public record ContextoDeConsulta(LocalDate hoy, ZoneId zona, String rutaActual, String pregunta) {

	/** Sin pregunta: la que llenan los llamadores que no la tienen. */
	public ContextoDeConsulta(LocalDate hoy, ZoneId zona, String rutaActual) {
		this(hoy, zona, rutaActual, null);
	}

	/** El mismo contexto con la pregunta del usuario. */
	public ContextoDeConsulta conPregunta(String pregunta) {
		return new ContextoDeConsulta(hoy, zona, rutaActual, pregunta);
	}
}
