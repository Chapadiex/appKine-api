package com.akine.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Una entrada de la lista de espera, con lo que recepcion necesita para resolverla: a quien llamar y
 * que le sirve.
 *
 * @param telefono para contactar: la entrada se resuelve por telefono o en el mostrador
 */
public record EntradaDeEsperaView(
		long id,
		long personaId,
		String personaNombre,
		String documento,
		String telefono,
		long ofertaId,
		String ofertaNombre,
		Long profesionalId,
		String profesionalNombre,
		Long casoId,
		Long planId,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		List<Integer> dias,
		LocalTime horaDesde,
		LocalTime horaHasta,
		String prioridad,
		String notas,
		String estado,
		Long turnoId,
		String motivoCierre,
		Instant creadaEn,
		long version) {
}
