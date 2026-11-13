package com.akine.scheduling.application;

/**
 * Un turno del calendario: el mismo turno de la recepcion mas el nombre del profesional.
 *
 * <p>El nombre no esta en {@link TurnoDelDiaView} y no se agrega ahi: la recepcion no lo pidio y
 * cambiarle la forma a la agenda del dia no es de este cambio. Se resuelve aca, en lote.
 *
 * @param profesionalNombre     nombre de la cuenta del profesional, o {@code null} si el turno no
 *                              tiene profesional o su cuenta ya no se puede resolver
 * @param fueraDeDisponibilidad solo en la agenda operativa: {@code true} si el turno sigue vivo y ya
 *                              no cae dentro de la disponibilidad efectiva de su profesional —un
 *                              bloqueo o un cambio de horario lo dejo afuera—, o sea "pendiente de
 *                              reubicacion". <b>Se deriva al leer y no se guarda</b> (regla 11: la
 *                              disponibilidad efectiva no se materializa). {@code null} cuando no se
 *                              evaluo: fuera de la agenda operativa, o un turno sin profesional
 */
public record TurnoDeCalendarioView(
		TurnoDelDiaView turno, String profesionalNombre, Boolean fueraDeDisponibilidad) {

	public TurnoDeCalendarioView(TurnoDelDiaView turno, String profesionalNombre) {
		this(turno, profesionalNombre, null);
	}

	public TurnoDeCalendarioView conFueraDeDisponibilidad(Boolean fuera) {
		return new TurnoDeCalendarioView(turno, profesionalNombre, fuera);
	}
}
