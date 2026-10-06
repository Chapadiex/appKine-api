package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.SerieView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(
		name = "SerieDeTurnos",
		description = "La regla con la que se generaron los turnos y los turnos tal como estan HOY. "
				+ "La regla no se reescribe cuando un turno se mueve: describe como se genero la serie.")
public record SerieDeTurnosResponse(

		@Schema(example = "12")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(example = "42")
		long ofertaId,

		@Schema(example = "128")
		long personaId,

		@Schema(description = "Ausente si la oferta no requiere profesional", example = "31")
		Long profesionalId,

		@Schema(allowableValues = {"SEMANAL"}, example = "SEMANAL")
		String frecuencia,

		@Schema(description = "Dias ISO, 1 = lunes")
		List<Integer> diasSemana,

		@Schema(description = "Hora local de inicio", type = "string", format = "time", example = "09:00:00")
		LocalTime hora,

		@Schema(example = "2026-10-12")
		LocalDate fechaDesde,

		@Schema(description = "Ausente si la serie termina por cantidad", example = "2026-12-21")
		LocalDate fechaHasta,

		@Schema(description = "Ausente si la serie termina por fecha", example = "10")
		Integer cantidad,

		@Schema(description = "Zona de la sede al crear la serie", example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(example = "2026-10-06T14:03:11Z")
		Instant creadaEn,

		@Schema(description = "Los turnos de la serie, del mas temprano al mas tarde, en cualquier estado")
		List<TurnoResponse> turnos) {

	public static SerieDeTurnosResponse de(SerieView vista) {
		return new SerieDeTurnosResponse(
				vista.id(), vista.consultorioId(), vista.ofertaId(), vista.personaId(),
				vista.profesionalId(), vista.frecuencia(), vista.diasSemana(), vista.hora(),
				vista.fechaDesde(), vista.fechaHasta(), vista.cantidad(), vista.timezone(),
				vista.creadaEn(), vista.turnos().stream().map(TurnoResponse::de).toList());
	}
}
