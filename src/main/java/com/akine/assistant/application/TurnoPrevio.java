package com.akine.assistant.application;

import java.util.Objects;

/**
 * Una pregunta anterior de la misma conversacion y lo que se entendio de ella. El historial lo manda
 * el cliente: el servidor no guarda conversaciones (diseno, §4.4).
 */
public record TurnoPrevio(String pregunta, Interpretacion interpretacion) {

	public TurnoPrevio {
		Objects.requireNonNull(pregunta, "pregunta");
		Objects.requireNonNull(interpretacion, "interpretacion");
	}
}
