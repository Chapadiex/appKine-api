package com.akine.organization.spi;

/**
 * Servicio que un centro contrata (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md §2.2).
 * Uno o los dos: la agenda, la caja, los cobros y los reportes los tiene cualquier centro.
 */
public enum Servicio {
	/** Historia clinica, casos, sesiones, coberturas, convenios y presentaciones a financiadores. */
	KINESIOLOGIA,
	/** Clases, inscripciones, asistencia, pases y abonos. */
	ACTIVIDADES
}
