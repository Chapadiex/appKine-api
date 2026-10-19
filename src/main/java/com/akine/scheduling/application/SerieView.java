package com.akine.scheduling.application;

import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.TurnoSerie;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Una serie con la regla que la genero, sus turnos tal como estan HOY (AKINE E-3) y las
 * ocurrencias que el alta salto, con su motivo (U-22).
 */
public record SerieView(
		long id,
		long consultorioId,
		long ofertaId,
		long personaId,
		Long profesionalId,
		String frecuencia,
		List<Integer> diasSemana,
		LocalTime hora,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		Integer cantidad,
		String timezone,
		Instant creadaEn,
		List<TurnoView> turnos,
		List<OcurrenciaOmitidaView> omitidas) {

	/** Sin omitidas: para las lecturas que solo muestran la regla. */
	public static SerieView de(TurnoSerie serie, List<TurnoView> turnos) {
		return de(serie, turnos, List.of());
	}

	public static SerieView de(
			TurnoSerie serie, List<TurnoView> turnos, List<OcurrenciaOmitidaView> omitidas) {

		ReglaDeRecurrencia regla = serie.getRegla();
		return new SerieView(
				serie.getId(),
				serie.getConsultorioId(),
				serie.getOfertaId(),
				serie.getPersonaId(),
				serie.getProfesionalMembershipId(),
				serie.getFrecuencia(),
				regla.dias().stream().sorted().map(DayOfWeek::getValue).toList(),
				regla.hora(),
				regla.desde(),
				regla.hasta(),
				regla.cantidad(),
				serie.getTimezone(),
				serie.getCreadaEn(),
				List.copyOf(turnos),
				List.copyOf(omitidas));
	}
}
