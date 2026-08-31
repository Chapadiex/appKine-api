package com.akine.scheduling.domain;

import java.time.LocalTime;

/**
 * Un rango de horas locales dentro de un mismo dia. Extremo superior EXCLUSIVO.
 *
 * <h2>Por que no se reusa {@code resource.domain.IntervaloLocal}</h2>
 *
 * <p>Porque es el dominio de otro modulo y ArchUnit lo prohibe: un modulo se alcanza por su
 * {@code spi} o por eventos, nunca importando su entity o su value object. La alternativa era
 * publicar {@code IntervaloLocal} a traves de {@code resource.spi}, y eso es peor: dejaria a
 * cualquier modulo que necesite cortar horas atado a la representacion que M05 eligio para
 * <b>componer reglas de disponibilidad</b>, que es otro problema.
 *
 * <p>Y la diferencia se ve en el tamano. {@code IntervaloLocal} tiene {@code restar},
 * {@code contiene} y {@code esContiguoCon} porque M05 resta cierres de bloques y une aperturas.
 * La agenda no compone reglas: recibe franjas ya resueltas y solo las corta. Este tipo es lo que
 * ese trabajo necesita y nada mas, asi que <b>no es una copia</b> — es un tipo mas chico que
 * casualmente comparte dos campos.
 *
 * <h2>Lo que si se copia, a proposito</h2>
 *
 * <p>{@link #FIN_DE_DIA}. {@link LocalTime} no puede representar las 24:00 —su maximo es
 * 23:59:59.999999999— y un bloque "hasta la medianoche" es completamente normal. M05 resuelve eso
 * con {@code LocalTime.MAX} y la base lo guarda como {@code '24:00:00'}. Las dos puntas tienen que
 * usar el MISMO centinela o la traduccion entre modulos pierde el ultimo slot de cada dia sin que
 * nada falle: la diferencia es de un nanosegundo en un campo que toda pantalla muestra redondeado.
 */
public record TramoLocal(LocalTime desde, LocalTime hasta) {

	/** El mismo centinela de fin de dia que {@code resource.domain.IntervaloLocal}. */
	public static final LocalTime FIN_DE_DIA = LocalTime.MAX;

	public TramoLocal {
		if (!hasta.isAfter(desde)) {
			throw new IllegalArgumentException(
					"Un tramo termina despues de empezar: " + desde + " -> " + hasta);
		}
	}
}
