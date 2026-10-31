package com.akine.scheduling.api.dto;

import com.akine.scheduling.api.dto.DisponibilidadDeTurnosResponse.HorarioDisponibleResponse;
import com.akine.scheduling.application.ValidacionDeHorarioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(
		name = "ValidacionDeHorario",
		description = "Si un horario se puede reservar. **Es informativo**: quien decide es la reserva, "
				+ "que revalida todo bajo el lock de la sede.")
public record ValidacionDeHorarioResponse(

		@Schema(allowableValues = {"DISPONIBLE", "NO_DISPONIBLE", "DISPONIBLE_CON_AVISO"}, example = "DISPONIBLE")
		String estado,

		@Schema(
				description = "Por que no se puede. Ausente si se puede.",
				allowableValues = {"PASADO", "OFERTA_NO_AGENDABLE", "CONTEXTO_CLINICO", "SLOT_NO_DISPONIBLE",
						"SLOT_COMPLETO", "PROFESIONAL_OCUPADO", "ESPACIO_OCUPADO"})
		String causa,

		@Schema(description = "La misma causa, para mostrarla", example = "El profesional ya tiene otro turno en ese horario.")
		String motivo,

		Instant inicio,

		@Schema(description = "El fin con quien atiende. Con cualquier profesional y varios libres todavia "
				+ "no hay quien atienda: es el de la oferta, y el de cada uno sale de su duracionPropiaMinutos.")
		Instant fin,

		@Schema(description = "Quien puede atender a esa hora")
		List<RecursoLibreResponse> profesionales,

		@Schema(description = "Boxes libres a esa hora, para ofrecer cambiar el asignado")
		List<RecursoLibreResponse> espaciosLibres,

		@Schema(description = "Turno con el que choca, si es un turno", example = "301")
		Long turnoEnConflictoId,

		Instant conflictoInicio,
		Instant conflictoFin,

		@Schema(description = "Lo que hay que saber y no impide reservar")
		List<String> avisos,

		@Schema(description = "Los horarios mas cercanos, si este no se puede")
		List<HorarioDisponibleResponse> alternativas) {

	public static ValidacionDeHorarioResponse de(ValidacionDeHorarioView vista) {
		return new ValidacionDeHorarioResponse(
				vista.estado(), vista.causa(), vista.motivo(), vista.inicio(), vista.fin(),
				vista.profesionales().stream().map(RecursoLibreResponse::de).toList(),
				vista.espaciosLibres().stream().map(RecursoLibreResponse::de).toList(),
				vista.turnoEnConflictoId(), vista.conflictoInicio(), vista.conflictoFin(),
				vista.avisos(),
				vista.alternativas().stream().map(HorarioDisponibleResponse::de).toList());
	}

	@Schema(name = "RecursoLibre", description = "Un profesional o un box, con su nombre.")
	public record RecursoLibreResponse(
			@Schema(example = "31") long id,
			@Schema(example = "Gomez, Laura") String nombre,
			@Schema(description = "Lo que tarda ese profesional en la prestacion, si no es lo que dura la "
					+ "prestacion. Ausente en un box, en quien usa la de la prestacion y al listar "
					+ "profesionales sin indicar prestacion.", example = "30")
			Integer duracionPropiaMinutos) {

		public static RecursoLibreResponse de(ValidacionDeHorarioView.RecursoLibreView vista) {
			return new RecursoLibreResponse(vista.id(), vista.nombre(), vista.duracionPropiaMinutos());
		}
	}
}
