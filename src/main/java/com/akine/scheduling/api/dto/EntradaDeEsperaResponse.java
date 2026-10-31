package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.EntradaDeEsperaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(name = "EntradaDeListaDeEspera", description = "Alguien que espera lugar para una prestacion. No ocupa la agenda.")
public record EntradaDeEsperaResponse(
		@Schema(example = "14") long id,
		@Schema(example = "128") long personaId,
		@Schema(example = "Perez, Ana") String personaNombre,
		@Schema(example = "DNI 30111222") String documento,
		@Schema(example = "351 555-1234") String telefono,
		@Schema(example = "42") long ofertaId,
		@Schema(example = "Kinesiologia") String ofertaNombre,
		@Schema(example = "31") Long profesionalId,
		@Schema(example = "Gomez, Laura") String profesionalNombre,
		Long casoId,
		Long planId,
		LocalDate fechaDesde,
		@Schema(description = "INCLUSIVA") LocalDate fechaHasta,
		@Schema(description = "Dias posibles, ISO. Vacio es cualquiera.") List<Integer> dias,
		@Schema(type = "string", format = "time") LocalTime horaDesde,
		@Schema(type = "string", format = "time") LocalTime horaHasta,
		@Schema(allowableValues = {"NORMAL", "ALTA", "URGENTE"}) String prioridad,
		String notas,
		@Schema(allowableValues = {"ACTIVA", "ASIGNADA", "DESCARTADA"}) String estado,
		@Schema(description = "El turno que se le asigno") Long turnoId,
		String motivoCierre,
		Instant creadaEn,
		long version) {

	public static EntradaDeEsperaResponse de(EntradaDeEsperaView vista) {
		return new EntradaDeEsperaResponse(
				vista.id(), vista.personaId(), vista.personaNombre(), vista.documento(), vista.telefono(),
				vista.ofertaId(), vista.ofertaNombre(), vista.profesionalId(), vista.profesionalNombre(),
				vista.casoId(), vista.planId(), vista.fechaDesde(), vista.fechaHasta(), vista.dias(),
				vista.horaDesde(), vista.horaHasta(), vista.prioridad(), vista.notas(), vista.estado(),
				vista.turnoId(), vista.motivoCierre(), vista.creadaEn(), vista.version());
	}
}
