package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Lo que manda quien pasa un turno a otra sede de la organizacion.
 *
 * <p>Lleva la oferta de destino: las ofertas son de cada sede, y la misma prestacion en otra sede es
 * otra oferta, con su duracion, su cupo y sus profesionales.
 */
@Schema(
		name = "TrasladarTurno",
		description = "Pasa el turno a otra sede: cancela el original (atribuido a la institucion, con el "
				+ "motivo) y reserva uno nuevo en la sede de destino que apunta al original. Las dos cosas "
				+ "ocurren juntas o ninguna.")
public record TrasladarTurnoRequest(

		@Schema(description = "Sede de destino, de la misma organizacion y distinta de la actual", example = "12")
		@NotNull(message = "La sede de destino es obligatoria")
		@Positive(message = "La sede de destino no es valida")
		Long consultorioDestinoId,

		@Schema(description = "Oferta de la sede de destino", example = "88")
		@NotNull(message = "La oferta de destino es obligatoria")
		@Positive(message = "La oferta de destino no es valida")
		Long ofertaId,

		@Schema(description = "Instante de inicio en la sede de destino, UTC", example = "2026-11-16T13:00:00Z")
		@NotNull(message = "El inicio es obligatorio")
		Instant inicio,

		@Schema(description = "Membership del profesional en la sede de destino. Obligatorio si la oferta lo "
				+ "requiere.", example = "41")
		@Positive(message = "El profesional no es valido")
		Long profesionalId,

		@Schema(description = "Box elegido a mano en la sede de destino. Si se omite, lo asigna el sistema.",
				example = "7")
		@Positive(message = "El espacio elegido no es valido")
		Long espacioId,

		@Schema(description = "Por que se cambia de sede. **Obligatorio** (DP-04).",
				example = "El paciente se mudo cerca de la otra sede")
		@NotBlank(message = "El motivo del cambio de sede es obligatorio")
		@Size(max = 250, message = "El motivo no puede superar los 250 caracteres")
		String motivo,

		@Schema(description = "Version que devolvio la ultima lectura del turno original.", example = "0")
		@NotNull(message = "La version esperada es obligatoria")
		@Min(value = 0, message = "La version esperada no puede ser negativa")
		Long expectedVersion) {
}
