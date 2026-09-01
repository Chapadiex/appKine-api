package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Lo que manda quien mueve un turno a otro horario.
 *
 * <p>No lleva persona ni oferta: <b>reprogramar no es reservar de nuevo</b>. El turno conserva su
 * identidad y su historial, y cambiarle el paciente o el servicio seria otro turno con la historia
 * equivocada pegada atras.
 */
@Schema(
		name = "ReprogramarTurno",
		description = "Mueve la reserva a otro intervalo. Es el MISMO turno: conserva id, paciente "
				+ "e historial. El servidor revalida el destino con los mismos controles que una "
				+ "reserva y bajo el mismo lock de sede.")
public record ReprogramarTurnoRequest(

		@Schema(
				description = "Instante de inicio del slot nuevo, UTC, tal como lo devolvio la agenda",
				example = "2026-09-22T12:00:00Z")
		@NotNull Instant inicio,

		@Schema(
				description = "Membership del profesional en el horario nuevo. **Puede ser otro**: "
						+ "mover un turno porque el profesional se ausento es el caso mas frecuente.",
				example = "31")
		@Positive Long profesionalId,

		@Schema(description = "Por que se mueve. **Obligatorio** (DP-04).", example = "El profesional pidio el dia")
		@NotBlank(message = "El motivo de reprogramacion es obligatorio")
		@Size(max = 300)
		String motivo,

		@Schema(description = "Version que devolvio la ultima lectura del turno.", example = "0")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
