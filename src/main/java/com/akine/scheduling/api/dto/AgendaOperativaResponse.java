package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.AgendaOperativaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "AgendaOperativa", description = "Todo lo que dibuja la agenda de una sede en una ventana.")
public record AgendaOperativaResponse(

		@Schema(example = "2026-10-26") LocalDate desde,
		@Schema(description = "EXCLUSIVO", example = "2026-11-02") LocalDate hasta,
		@Schema(example = "America/Argentina/Cordoba") String timezone,
		List<ProfesionalDeAgendaResponse> profesionales,
		List<EspacioDeAgendaResponse> espacios,
		List<TurnoDeCalendarioResponse> turnos,
		List<ClaseEnAgendaResponse> clases,
		List<BloqueoEnAgendaResponse> bloqueos) {

	public static AgendaOperativaResponse de(AgendaOperativaView vista) {
		return new AgendaOperativaResponse(vista.desde(), vista.hasta(), vista.timezone(),
				vista.profesionales().stream().map(profesional -> new ProfesionalDeAgendaResponse(
						profesional.membershipId(), profesional.nombre(),
						profesional.franjas().stream()
								.map(franja -> new FranjaDeAgendaResponse(franja.desde(), franja.hasta())).toList())).toList(),
				vista.espacios().stream()
						.map(espacio -> new EspacioDeAgendaResponse(espacio.id(), espacio.nombre(), espacio.tipo())).toList(),
				vista.turnos().stream().map(TurnoDeCalendarioResponse::de).toList(),
				vista.clases().stream().map(clase -> new ClaseEnAgendaResponse(
						clase.claseId(), clase.inicio(), clase.fin(), clase.estado(), clase.titulo(), clase.ofertaNombre(),
						clase.profesionalId(), clase.espacioId(), clase.capacidad(), clase.ocupados())).toList(),
				vista.bloqueos().stream().map(bloqueo -> new BloqueoEnAgendaResponse(
						bloqueo.excepcionId(), bloqueo.membershipId(), bloqueo.motivo(), bloqueo.notas(),
						bloqueo.desde(), bloqueo.hasta())).toList());
	}

	@Schema(name = "ProfesionalDeAgenda", description = "Una columna de la agenda: quien atiende y cuando.")
	public record ProfesionalDeAgendaResponse(
			@Schema(example = "31") long membershipId,
			@Schema(example = "Gomez, Laura") String nombre,
			@Schema(description = "Cuando atiende en la ventana. Lo que no esta aca no se puede reservar.")
			List<FranjaDeAgendaResponse> franjas) {
	}

	@Schema(name = "FranjaDeAgenda")
	public record FranjaDeAgendaResponse(Instant desde, Instant hasta) {
	}

	@Schema(name = "EspacioDeAgenda")
	public record EspacioDeAgendaResponse(
			@Schema(example = "55") long id,
			@Schema(example = "Box 3") String nombre,
			@Schema(example = "BOX") String tipo) {
	}

	@Schema(name = "ClaseEnAgenda", description = "Una clase grupal: ocupa profesional y box igual que un turno.")
	public record ClaseEnAgendaResponse(
			@Schema(example = "7") long claseId,
			Instant inicio,
			Instant fin,
			@Schema(example = "PROGRAMADA") String estado,
			String titulo,
			String ofertaNombre,
			Long profesionalId,
			Long espacioId,
			int capacidad,
			int ocupados) {
	}

	@Schema(name = "BloqueoEnAgenda", description = "Un cierre de agenda, un tramo por dia.")
	public record BloqueoEnAgendaResponse(
			@Schema(description = "El cierre: se da de baja una sola vez aunque abarque varios dias", example = "14")
			long excepcionId,
			@Schema(description = "Ausente = sede entera", example = "31") Long membershipId,
			@Schema(allowableValues = {"AUSENCIA", "LICENCIA", "CAPACITACION", "FERIADO", "BLOQUEO", "AMPLIACION", "OTRO"}, example = "CAPACITACION")
			String motivo,
			String notas,
			Instant desde,
			Instant hasta) {
	}
}
