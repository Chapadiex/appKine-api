package com.akine.scheduling.api.dto;

import com.akine.scheduling.api.dto.DisponibilidadDeTurnosResponse.HorarioDisponibleResponse;
import com.akine.scheduling.application.PrevisualizacionDeSerieView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "PrevisualizacionDeSerie", description = "Lo que se reservaria. No crea nada: lo que se confirma es lo que quede de esto.")
public record PrevisualizacionDeSerieResponse(

		@Schema(example = "42") long ofertaId,
		@Schema(example = "Kinesiologia") String ofertaNombre,
		@Schema(description = "La del profesional de la serie, que puede tener duracion propia.", example = "45")
		int duracionMinutos,
		@Schema(example = "America/Argentina/Cordoba") String timezone,
		@Schema(description = "Sesiones que le quedan al plan para esa prestacion, si hay plan. Es el tope del alta.", example = "5")
		Integer pendientesDelPlan,
		List<OcurrenciaPrevistaResponse> ocurrencias) {

	public static PrevisualizacionDeSerieResponse de(PrevisualizacionDeSerieView vista) {
		return new PrevisualizacionDeSerieResponse(
				vista.ofertaId(), vista.ofertaNombre(), vista.duracionMinutos(), vista.timezone(), vista.pendientesDelPlan(),
				vista.ocurrencias().stream().map(ocurrencia -> new OcurrenciaPrevistaResponse(
						ocurrencia.numero(), ocurrencia.inicio(), ocurrencia.fin(), ocurrencia.profesionalId(),
						ocurrencia.profesionalNombre(), ocurrencia.espacioNombre(), ocurrencia.estado(), ocurrencia.motivo(),
						ocurrencia.sugerencia() == null ? null : HorarioDisponibleResponse.de(ocurrencia.sugerencia()))).toList());
	}

	@Schema(name = "OcurrenciaPrevista")
	public record OcurrenciaPrevistaResponse(
			@Schema(example = "1") int numero,
			Instant inicio,
			Instant fin,
			@Schema(example = "31") Long profesionalId,
			@Schema(example = "Gomez, Laura") String profesionalNombre,
			@Schema(example = "Box 3") String espacioNombre,
			@Schema(allowableValues = {"DISPONIBLE", "NO_DISPONIBLE", "FERIADO"}, example = "DISPONIBLE") String estado,
			@Schema(description = "Por que no se puede") String motivo,
			@Schema(description = "El horario mas cercano si este no se puede") HorarioDisponibleResponse sugerencia) {
	}
}
