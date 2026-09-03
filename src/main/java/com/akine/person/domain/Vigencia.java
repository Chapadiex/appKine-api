package com.akine.person.domain;

import java.time.LocalDate;

/**
 * Un periodo con fin INCLUSIVO, y las tres preguntas que se le hacen (M17).
 *
 * <p>{@code hasta == null} significa "sin fin previsto", no "termina hoy". {@code hasta} es el
 * ULTIMO dia en que el periodo vale: lo fijo 03.03 para los planes, lo repitieron V42 y V43, y
 * cambiarlo aca dejaria a la orden y a la autorizacion contando un dia menos que la cobertura que
 * las respalda.
 *
 * <p><b>No se puede reusar {@code contracting.domain.Vigencia}</b>, que es identica: es de otro
 * modulo y ArchUnit rechaza importar su {@code domain}. Mismo motivo por el que {@code person} y
 * {@code resource} tienen cada uno su {@code MarcaTemporal}.
 *
 * <p><b>{@link #seSolapaCon(Vigencia)} es lo que ningun unique de MySQL puede expresar.</b>
 * 01-01..06-30 y 03-01..12-31 se pisan sin compartir un solo valor de columna, y MySQL 8.4 no
 * tiene exclusion constraints. Esta clase sabe decidirlo entre dos instancias; que la regla se
 * cumpla frente a dos escrituras concurrentes depende del lock de
 * {@code autorizacion_persona_lock}.
 */
public record Vigencia(LocalDate desde, LocalDate hasta) {

	public Vigencia {
		if (desde == null) {
			throw new IllegalArgumentException("La vigencia necesita una fecha de inicio");
		}
		if (hasta != null && hasta.isBefore(desde)) {
			throw new IllegalArgumentException(
					"La vigencia no puede terminar antes de empezar: " + desde + " a " + hasta);
		}
	}

	/** Dos periodos abiertos siempre se pisan; uno cerrado se compara contra el inicio del otro. */
	public boolean seSolapaCon(Vigencia otra) {
		return noTerminaAntesDe(this, otra.desde()) && noTerminaAntesDe(otra, this.desde());
	}

	/** El dia cae dentro, con {@code hasta} INCLUSIVO. Una fecha nula nunca cae dentro. */
	public boolean cubre(LocalDate fecha) {
		if (fecha == null || fecha.isBefore(desde)) {
			return false;
		}
		return hasta == null || !fecha.isAfter(hasta);
	}

	/**
	 * Dias que faltan para el vencimiento, o {@code null} si no hay fin previsto.
	 *
	 * <p>Es lo que hace posible RF-M17-006 sin ningun job: la alerta de vencimiento es un numero
	 * que se calcula al leer, no un estado que alguien tiene que acordarse de mover. Negativo
	 * cuando ya vencio, y eso tambien es informacion util.
	 */
	public Long diasHasta(LocalDate fecha) {
		if (hasta == null || fecha == null) {
			return null;
		}
		return java.time.temporal.ChronoUnit.DAYS.between(fecha, hasta);
	}

	private static boolean noTerminaAntesDe(Vigencia periodo, LocalDate fecha) {
		return periodo.hasta() == null || !periodo.hasta().isBefore(fecha);
	}

	@Override
	public String toString() {
		return desde + " a " + (hasta == null ? "sin fin previsto" : hasta.toString());
	}
}
