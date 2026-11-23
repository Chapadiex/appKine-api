package com.akine.assistant.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Ajustes del asistente de datos que necesita la aplicacion.
 *
 * @param enabled interruptor global ({@code akine.asistente.enabled}), apagado por defecto
 * @param plazo   cuanto se espera al modelo antes de responder que no esta disponible
 * @param sql     consultas libres sobre {@code asistente_sql} ({@code akine.asistente.sql.enabled}),
 *                apagadas por defecto
 */
public record AsistenteAjustes(boolean enabled, Duration plazo, boolean sql, boolean acciones,
		Duration vigenciaDeAccion) {

	/** Sin acciones. */
	public AsistenteAjustes(boolean enabled, Duration plazo, boolean sql) {
		this(enabled, plazo, sql, false, Duration.ofMinutes(15));
	}


	/** Sin consultas libres. */
	public AsistenteAjustes(boolean enabled, Duration plazo) {
		this(enabled, plazo, false);
	}

	public AsistenteAjustes {
		Objects.requireNonNull(plazo, "plazo");
		if (plazo.isNegative() || plazo.isZero()) {
			throw new IllegalArgumentException("El plazo del modelo tiene que ser positivo");
		}
	}
}
