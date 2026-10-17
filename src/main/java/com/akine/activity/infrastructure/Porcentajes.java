package com.akine.activity.infrastructure;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * El porcentaje de los indicadores de {@code activity} en los reportes (AKINE-09.01).
 *
 * <p>Dos decimales, {@code HALF_UP}, en {@code BigDecimal}: la misma forma que la tasa de
 * ausentismo de los turnos. Con denominador cero devuelve <b>cero explicito</b> y no una division
 * que explota ni un {@code null} que la pantalla leeria como "sin dato": un periodo sin clases tiene
 * ocupacion cero, no indefinida.
 */
final class Porcentajes {

	private static final BigDecimal CIEN = BigDecimal.valueOf(100);

	private Porcentajes() {
	}

	static BigDecimal de(long parte, long total) {
		if (total <= 0) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(parte)
				.multiply(CIEN)
				.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
	}
}
