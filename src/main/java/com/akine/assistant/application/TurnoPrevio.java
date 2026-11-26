package com.akine.assistant.application;

import java.util.List;
import java.util.Objects;

/**
 * Una pregunta anterior de la misma conversacion, el texto que se le respondio y las personas que esa
 * respuesta nombro. El historial lo manda el cliente: el servidor no guarda conversaciones.
 *
 * <p>Nunca lleva resultados de herramientas: un resultado fabricado por el cliente seria una via de
 * inyeccion y de cifras falsas. Los ids son pistas; cada herramienta los valida contra el tenant
 * (docs/diseno/AKINE-asistente-agente.md §5.1).
 *
 * @param respuesta  el texto que devolvio el servidor, o {@code null} si el cliente no lo mando
 * @param personaIds las personas nombradas en esa respuesta
 */
public record TurnoPrevio(String pregunta, String respuesta, List<Long> personaIds) {

	public TurnoPrevio {
		Objects.requireNonNull(pregunta, "pregunta");
		personaIds = personaIds == null ? List.of() : List.copyOf(personaIds);
	}
}
