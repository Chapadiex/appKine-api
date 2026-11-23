package com.akine.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/**
 * Cuantos dias antes de un vencimiento se avisa (19/11/2026, docs/diseno/AKINE-cobro-mensual.md):
 * 15, 10, 5, 2 y 1. Vale igual para la prueba gratis y para el periodo pagado.
 *
 * <p>Un correo por hito, y solo el ultimo alcanzado: si el worker estuvo caido del dia 14 al 4, al
 * volver manda el de 5 dias y no los de 10 y 5 juntos.
 */
final class HitosDeAviso {

	/** Dias antes del vencimiento en que se avisa, de mayor a menor. */
	static final List<Integer> DIAS = List.of(15, 10, 5, 2, 1);

	private static final Duration DIA = Duration.ofDays(1);

	private HitosDeAviso() {
	}

	/**
	 * El hito alcanzado mas reciente: el menor {@code m} de {@link #DIAS} con
	 * {@code ahora >= vence - m dias}. Vacio si falta mas que el primero o si ya vencio.
	 */
	static OptionalInt alcanzado(Instant vence, Instant ahora) {
		if (!ahora.isBefore(vence)) {
			return OptionalInt.empty();
		}
		OptionalInt hito = OptionalInt.empty();
		for (int dias : DIAS) {
			if (!ahora.isBefore(vence.minus(Duration.ofDays(dias)))) {
				hito = OptionalInt.of(dias);
			}
		}
		return hito;
	}

	/** Dias que faltan, redondeados para arriba: a 4 dias y 3 horas del vencimiento faltan 5. */
	static long diasRestantes(Instant vence, Instant ahora) {
		Duration falta = Duration.between(ahora, vence);
		if (falta.isNegative() || falta.isZero()) {
			return 0;
		}
		long dias = falta.dividedBy(DIA);
		return falta.equals(DIA.multipliedBy(dias)) ? dias : dias + 1;
	}
}
