package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja del perfil clinico de una persona, dejandola en el padron (RF-M07-005).
 *
 * <p>El motivo es obligatorio y la activacion lo tiene opcional. No es una inconsistencia: activar
 * amplia lo que se puede hacer con una ficha y dar de baja lo restringe, y la operacion que
 * restringe es la que alguien va a tener que justificar despues.
 */
@Schema(description = "Baja del perfil clinico. La persona sigue vigente en el padron")
public record BajaDePerfilPacienteRequest(

		@Schema(
				description = "Motivo declarado de la baja. Obligatorio",
				example = "Se cargo por error: viene solo a clases grupales",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo) {
}
