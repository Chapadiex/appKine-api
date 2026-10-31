package com.akine.scheduling.application;

import com.akine.scheduling.domain.PrioridadListaDeEspera;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public record AltaEnListaDeEsperaCommand(
		long personaId,
		long ofertaId,
		Long profesionalId,
		Long casoId,
		Long planId,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		Set<DayOfWeek> dias,
		LocalTime horaDesde,
		LocalTime horaHasta,
		PrioridadListaDeEspera prioridad,
		String notas) {
}
