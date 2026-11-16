package com.akine.assistant.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Ajustes del dictado por voz (docs/diseno/AKINE-dictado-por-voz.md), ya validados.
 *
 * @param enabled  el interruptor {@code akine.dictado.enabled}; apagado, todo responde 409
 * @param plazo    cuanto se espera al transcriptor
 * @param maxBytes tope del audio que se acepta
 */
public record DictadoAjustes(boolean enabled, Duration plazo, long maxBytes) {

	public DictadoAjustes {
		Objects.requireNonNull(plazo, "plazo");
		if (plazo.isNegative() || plazo.isZero()) {
			throw new IllegalArgumentException("El plazo del transcriptor tiene que ser positivo");
		}
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("El tope del audio tiene que ser positivo");
		}
	}
}
