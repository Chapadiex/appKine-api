package com.akine.assistant.application;

import java.time.LocalDate;
import java.util.Objects;

/** Un intervalo de dias de la sede, con los dos extremos incluidos. */
public record RangoDeDias(LocalDate desde, LocalDate hasta) {

	public RangoDeDias {
		Objects.requireNonNull(desde, "desde");
		Objects.requireNonNull(hasta, "hasta");
		if (hasta.isBefore(desde)) {
			throw new IllegalArgumentException("El rango termina antes de empezar: " + desde + ".." + hasta);
		}
	}

	/** Si el rango es un solo dia. */
	public boolean esUnDia() {
		return desde.equals(hasta);
	}

	public static RangoDeDias delDia(LocalDate dia) {
		return new RangoDeDias(dia, dia);
	}
}
