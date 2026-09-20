package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.EventoDeAgendaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un evento de la agenda unificada (RF-M12-013).
 *
 * <p><b>Union discriminada por {@code tipo}.</b> El cliente lee ese campo y sabe que acciones
 * ofrecer y a que endpoint mandarlas: un turno se cancela en
 * {@code /turnos/{id}/cancelacion} y una clase en {@code /clases/{id}/cancelacion}. La grilla los
 * dibuja juntos; el backend no los fusiona.
 *
 * <p><b>El id NO es unico entre tipos.</b> Un turno 7 y una clase 7 existen a la vez: la clave real
 * de una fila es el par {@code (tipo, eventoId)}. Un cliente que use solo el id mezcla dos eventos
 * distintos.
 */
@Schema(
		name = "EventoDeAgenda",
		description = "Turno o clase, en la misma grilla. Son entidades separadas con reglas "
				+ "propias: lo unico comun es esta proyeccion de lectura.")
public record EventoDeAgendaResponse(

		@Schema(description = "TURNO o CLASE", example = "CLASE") String tipo,

		@Schema(
				description = "Id dentro de su propio tipo. No es unico entre tipos: la clave es "
						+ "(tipo, eventoId).",
				example = "77")
		long eventoId,

		@Schema(example = "2026-10-05T12:00:00Z") Instant inicio,
		@Schema(example = "2026-10-05T13:00:00Z") Instant fin,
		@Schema(description = "Estado dentro de la maquina de su propio tipo", example = "PROGRAMADA")
		String estado,
		@Schema(example = "45") long ofertaId,
		@Schema(example = "Pilates") String ofertaNombre,
		@Schema(description = "Solo para CLASE", example = "Pilates - grupo avanzado") String titulo,
		@Schema(example = "31") Long profesionalId,
		@Schema(example = "8") Long espacioId,
		@Schema(description = "Cuantas personas admite el evento", example = "8") int capacidad,
		@Schema(description = "Cuantos lugares estan tomados", example = "0") int ocupados,

		@Schema(
				description = "Solo para TURNO. Una clase no expone participantes en la grilla.",
				example = "128")
		Long personaId) {

	public static EventoDeAgendaResponse de(EventoDeAgendaView view) {
		return new EventoDeAgendaResponse(
				view.tipo(),
				view.eventoId(),
				view.inicio(),
				view.fin(),
				view.estado(),
				view.ofertaId(),
				view.ofertaNombre(),
				view.titulo(),
				view.profesionalId(),
				view.espacioId(),
				view.capacidad(),
				view.ocupados(),
				view.personaId());
	}
}
