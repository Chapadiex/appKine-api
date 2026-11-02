package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.DisponibilidadView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "DisponibilidadDeTurnos", description = "Horarios libres de una prestacion, del mas cercano al mas lejano.")
public record DisponibilidadDeTurnosResponse(

		@Schema(example = "42") long ofertaId,
		@Schema(example = "Kinesiologia - sesion individual") String ofertaNombre,
		@Schema(description = "La del profesional si se pidio uno, y si no la de la prestacion. Un profesional "
				+ "con duracion propia tiene otra: cada horario trae su `hasta`.", example = "45")
		int duracionMinutos,
		@Schema(description = "`true` si el turno ocupa un box") boolean requiereEspacio,
		@Schema(example = "America/Argentina/Cordoba") String timezone,
		@Schema(description = "Ultimo dia que se miro, EXCLUSIVO", example = "2026-11-30") LocalDate buscadoHasta,
		@Schema(description = "`true` si habia mas horarios que el limite pedido") boolean truncado,

		@Schema(description = "Por que hubo dias sin horarios, sin repetir. Explica una busqueda vacia. "
				+ "Mismos valores que `DiaDeAgenda.motivoSinSlots`.")
		List<String> motivosSinHorario,

		List<HorarioDisponibleResponse> horarios) {

	public static DisponibilidadDeTurnosResponse de(DisponibilidadView vista) {
		return new DisponibilidadDeTurnosResponse(
				vista.ofertaId(), vista.ofertaNombre(), vista.duracionMinutos(), vista.requiereEspacio(),
				vista.timezone(), vista.buscadoHasta(), vista.truncado(), vista.motivosSinHorario(),
				vista.horarios().stream().map(HorarioDisponibleResponse::de).toList());
	}

	@Schema(name = "HorarioDisponible", description = "Un horario que se puede reservar ahora.")
	public record HorarioDisponibleResponse(

			@Schema(example = "2026-10-30T17:30:00Z") Instant desde,
			@Schema(example = "2026-10-30T18:15:00Z") Instant hasta,
			@Schema(example = "31") Long profesionalId,
			@Schema(example = "Gomez, Laura") String profesionalNombre,

			@Schema(description = "Box que tomaria si se reservara ahora. Sugerencia: lo clava la reserva.", example = "55")
			Long espacioId,

			@Schema(example = "Box 3") String espacioNombre,
			@Schema(example = "1") int cupoLibre) {

		public static HorarioDisponibleResponse de(DisponibilidadView.HorarioDisponibleView vista) {
			return new HorarioDisponibleResponse(vista.desde(), vista.hasta(), vista.profesionalId(),
					vista.profesionalNombre(), vista.espacioId(), vista.espacioNombre(), vista.cupoLibre());
		}
	}
}
