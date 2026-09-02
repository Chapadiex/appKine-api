package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja logica de un adjunto (RF-M25-004).
 *
 * <p>No lleva {@code expectedVersion}, a diferencia de la baja de una persona: un adjunto no se
 * edita en paralelo —lo unico mutable es su clasificacion— y el unico conflicto real, "ya estaba
 * dado de baja", se detecta por estado y devuelve 409 igual.
 */
@Schema(description = "Baja logica de un adjunto. El contenido se sigue pudiendo descargar")
public record BajaDeAdjuntoRequest(

		@Schema(
				description = "Motivo declarado de la baja. Obligatorio",
				example = "Credencial vencida; se cargo la nueva",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo) {
}
