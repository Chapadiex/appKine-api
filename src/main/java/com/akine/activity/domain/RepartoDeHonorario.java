package com.akine.activity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * El reparto del honorario de una clase entre sus instructores (AKINE-08.09b, DP-38).
 *
 * <p><b>Partes iguales, redondeadas al centavo de modo que la suma cierre exacta, y el resto al
 * primero en el orden estable</b> (el principal y despues los adicionales por membership). Con
 * $1.000,00 entre tres: 333,34 / 333,33 / 333,33.
 *
 * <p>Vive aca y en ningun otro lado: si el borrador y una prueba calcularan cada uno su reparto,
 * divergerian en el primer centavo.
 */
public final class RepartoDeHonorario {

	private RepartoDeHonorario() {
	}

	/** El total de la clase: tarifa por participante por presentes, al centavo. */
	public static BigDecimal totalDeLaClase(BigDecimal importePorParticipante, int presentes) {
		return importePorParticipante.multiply(BigDecimal.valueOf(presentes)).setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * La parte del instructor en la {@code posicion} (1 = el primero) de {@code instructores}.
	 *
	 * @throws IllegalArgumentException sin instructores o con una posicion fuera de rango
	 */
	public static BigDecimal parte(BigDecimal totalDeLaClase, int instructores, int posicion) {
		if (instructores < 1 || posicion < 1 || posicion > instructores) {
			throw new IllegalArgumentException(
					"Posicion " + posicion + " fuera de un reparto entre " + instructores);
		}
		BigDecimal total = totalDeLaClase.setScale(2, RoundingMode.HALF_UP);
		BigDecimal base = total.divide(BigDecimal.valueOf(instructores), 2, RoundingMode.DOWN);
		if (posicion > 1) {
			return base;
		}
		BigDecimal resto = total.subtract(base.multiply(BigDecimal.valueOf(instructores)));
		return base.add(resto);
	}
}
