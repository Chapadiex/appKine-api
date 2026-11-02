package com.akine.scheduling.api.dto;

import com.akine.scheduling.domain.AlcanceDeSerie;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(
		name = "ReprogramarSerieDeTurnos",
		description = "Mueve los turnos pendientes del alcance con el MISMO desplazamiento que lleva "
				+ "al pivote a su horario nuevo, medido en hora local de la sede. Cada turno conserva "
				+ "su id y su historial. Todo o nada: si un destino no tiene lugar, no se mueve "
				+ "ninguno y el 409 lo nombra en `ocurrenciaInicio`.")
public record ReprogramarSerieDeTurnosRequest(

		@Schema(description = "Sobre que turnos de la serie actua", example = "ESTE_Y_SIGUIENTES")
		@NotNull(message = "El alcance es obligatorio") AlcanceDeSerie alcance,

		@Schema(
				description = "Turno pivote: de su horario actual y el nuevo sale el desplazamiento. "
						+ "Obligatorio en los tres alcances.",
				example = "301")
		@NotNull(message = "El turno es obligatorio") @Positive(message = "El turno no es valido") Long turnoId,

		@Schema(description = "Horario nuevo DEL PIVOTE, UTC", example = "2026-10-13T13:00:00Z")
		@NotNull(message = "El inicio es obligatorio") Instant inicio,

		@Schema(
				description = "Si viene, todos los afectados pasan a este profesional. Si no, cada uno "
						+ "conserva el suyo.",
				example = "31")
		@Positive(message = "El profesional no es valido") Long profesionalId,

		@Schema(description = "Por que se mueve. **Obligatorio** (DP-04).", example = "El profesional deja de atender los lunes")
		@NotBlank(message = "El motivo de reprogramacion es obligatorio")
		@Size(max = 300, message = "El motivo no puede superar los 300 caracteres")
		String motivo,

		@Schema(
				description = "**Confirmacion explicita**: la cantidad de turnos afectados que mostro la "
						+ "previsualizacion.",
				example = "6")
		@NotNull(message = "La cantidad confirmada es obligatoria")
		@Min(value = 1, message = "La cantidad confirmada no puede ser menor que 1")
		Integer cantidadConfirmada) {
}
