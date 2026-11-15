package com.akine.assistant.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Ajustes del asistente de datos que necesita la aplicacion.
 *
 * @param enabled interruptor global ({@code akine.asistente.enabled}), apagado por defecto
 * @param plazo   cuanto se espera al modelo antes de responder que no esta disponible
 */
public record AsistenteAjustes(boolean enabled, Duration plazo) {

	public AsistenteAjustes {
		Objects.requireNonNull(plazo, "plazo");
		if (plazo.isNegative() || plazo.isZero()) {
			throw new IllegalArgumentException("El plazo del modelo tiene que ser positivo");
		}
	}
}
