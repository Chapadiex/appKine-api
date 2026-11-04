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
		List<BloqueoEnAgendaResponse> bloqueos,
		@Schema(description = "Intervalos en que la persona de una columna esta ocupada en OTRA sede de la "
				+ "organizacion (turno o clase). La celda no esta libre aunque en esta sede no haya nada.")
		List<OcupacionEnOtraSedeResponse> ocupacionesEnOtrasSedes,
		@Schema(description = "Turnos y clases de la misma persona que ya se pisan (datos anteriores a la "
				+ "regla entre sedes). Se calculan al leer; nada se modifica.")
		List<ConflictoDeAgendaResponse> conflictos) {

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
						bloqueo.desde(), bloqueo.hasta())).toList(),
				vista.ocupacionesEnOtrasSedes().stream().map(o -> new OcupacionEnOtraSedeResponse(
						o.membershipId(), o.tipo(), o.consultorioId(), o.inicio(), o.fin())).toList(),
				vista.conflictos().stream().map(c -> new ConflictoDeAgendaResponse(
						c.membershipId(), c.turnoId(), c.turnoConsultorioId(), c.claseId(), c.claseConsultorioId(),
						c.inicio(), c.fin())).toList());
	}

	@Schema(name = "OcupacionEnOtraSede", description = "La persona de la columna esta ocupada en otra sede. "
			+ "Sin paciente ni id.")
	public record OcupacionEnOtraSedeResponse(
			@Schema(description = "La columna: membership de esta sede", example = "31") Long membershipId,
			@Schema(allowableValues = {"TURNO", "CLASE"}, example = "CLASE") String tipo,
			@Schema(example = "12") long consultorioId,
			Instant inicio,
			Instant fin) {
	}

	@Schema(name = "ConflictoDeAgenda", description = "Un turno y una clase de la misma persona que se pisan. "
			+ "El id viaja solo del lado que es de esta sede.")
	public record ConflictoDeAgendaResponse(
			@Schema(description = "La columna: membership de esta sede", example = "31") Long membershipId,
			@Schema(description = "Ausente si el turno es de otra sede", example = "501") Long turnoId,
			long turnoConsultorioId,
			@Schema(description = "Ausente si la clase es de otra sede", example = "7") Long claseId,
			long claseConsultorioId,
			@Schema(description = "Comienzo del tramo en que se pisan") Instant inicio,
			@Schema(description = "Fin del tramo en que se pisan") Instant fin) {
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
