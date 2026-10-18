package com.akine.activity.application;

import java.time.Instant;
import java.time.LocalDate;

/**
 * El rango de fechas que {@code activity} acepta de un cliente (AKINE-09.02).
 *
 * <p>Jackson acepta cualquier {@link Instant} o {@link LocalDate} que Java pueda representar, hasta
 * el anio mil millones. Lo que pasaba con eso:
 *
 * <ul>
 *   <li>una clase con {@code inicio = +1000000000-01-01T00:00:00Z} terminaba en <b>500</b>:
 *       {@code atZone} lanza {@code DateTimeException} al pasar a la zona de la sede;</li>
 *   <li>un abono con {@code periodoDesde = +999999999-12-31} terminaba en <b>500</b> al sumarle la
 *       duracion;</li>
 *   <li>un anio 10000 entraba al dominio, el {@code INSERT} fallaba porque {@code DATE} y
 *       {@code DATETIME} de MySQL llegan a 9999, y la respuesta era un 409 {@code conflict} que
 *       decia "choca con un dato ya existente".</li>
 * </ul>
 *
 * <p>El tope deja margen bajo el 9999 de MySQL para las sumas que el dominio hace despues
 * (duracion de un pase, periodo de un abono, zona de la sede). Nadie programa una clase en el anio
 * 9001: es un 400 y no un rango de negocio.
 */
final class FechasAdmitidas {

	static final LocalDate PRIMERA = LocalDate.of(1000, 1, 1);
	static final LocalDate ULTIMA = LocalDate.of(9000, 12, 31);
	private static final Instant PRIMER_INSTANTE = Instant.parse("1000-01-02T00:00:00Z");
	private static final Instant ULTIMO_INSTANTE = Instant.parse("9000-12-31T00:00:00Z");

	private FechasAdmitidas() {
	}

	/** {@code null} pasa: si el campo es obligatorio lo dice otra validacion. */
	static void exigir(LocalDate fecha, String campo) {
		if (fecha != null && (fecha.isBefore(PRIMERA) || fecha.isAfter(ULTIMA))) {
			throw new IllegalArgumentException(
					campo + " tiene que estar entre " + PRIMERA + " y " + ULTIMA);
		}
	}

	/** {@code null} pasa: si el campo es obligatorio lo dice otra validacion. */
	static void exigir(Instant instante, String campo) {
		if (instante != null
				&& (instante.isBefore(PRIMER_INSTANTE) || instante.isAfter(ULTIMO_INSTANTE))) {
			throw new IllegalArgumentException(
					campo + " tiene que estar entre " + PRIMERA + " y " + ULTIMA);
		}
	}
}
