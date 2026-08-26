package com.akine.resource.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Intervalo de horas locales dentro de un mismo dia. Extremo superior EXCLUSIVO.
 *
 * <h2>Por que la medianoche final es un caso especial</h2>
 *
 * <p>{@link LocalTime} no puede representar las 24:00: su maximo es 23:59:59.999999999. Pero
 * un bloque que va "hasta la medianoche" es completamente normal, y la base lo guarda como
 * {@code '24:00:00'}.
 *
 * <p>Adentro de esta clase, el fin de dia se representa con {@link #FIN_DE_DIA}, que es
 * {@code LocalTime.MAX}, y la conversion desde y hacia la base la hace el mapper
 * ({@link HoraLocalConverter}). Comparar {@code LocalTime.MAX} funciona porque es
 * estrictamente mayor que cualquier hora real; lo unico que no hay que hacer es sumarle nada.
 */
public record IntervaloLocal(LocalTime desde, LocalTime hasta) {

	public static final LocalTime FIN_DE_DIA = LocalTime.MAX;

	public IntervaloLocal {
		if (!hasta.isAfter(desde)) {
			throw new IllegalArgumentException(
					"Un intervalo termina despues de empezar: " + desde + " -> " + hasta);
		}
	}

	public boolean solapaCon(IntervaloLocal otro) {
		return desde.isBefore(otro.hasta) && otro.desde.isBefore(hasta);
	}

	public boolean contiene(IntervaloLocal otro) {
		return !desde.isAfter(otro.desde) && !hasta.isBefore(otro.hasta);
	}

	/**
	 * Resta {@code otro} de este intervalo. Devuelve 0, 1 o 2 intervalos.
	 *
	 * <p>Dos es el caso que importa y el que se olvida: un cierre en el medio de un bloque
	 * —almuerzo, reunion— parte la franja en dos y ambas mitades siguen siendo atencion.
	 */
	public List<IntervaloLocal> restar(IntervaloLocal otro) {
		if (!solapaCon(otro)) {
			return List.of(this);
		}
		List<IntervaloLocal> resto = new ArrayList<>(2);
		if (desde.isBefore(otro.desde)) {
			resto.add(new IntervaloLocal(desde, otro.desde));
		}
		if (otro.hasta.isBefore(hasta)) {
			resto.add(new IntervaloLocal(otro.hasta, hasta));
		}
		return resto;
	}

	/** Union solo si se tocan o se solapan; si no, no hay un unico intervalo que los cubra. */
	public boolean esContiguoCon(IntervaloLocal otro) {
		return solapaCon(otro) || hasta.equals(otro.desde) || otro.hasta.equals(desde);
	}
}
