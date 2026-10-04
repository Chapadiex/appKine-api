package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Reemplazo del resumen clinico minimo de una historia (RF-M09-001).
 *
 * <p>{@code expectedVersion} evita que el segundo en guardar pise en silencio lo que escribio el
 * primero: si la historia cambio desde que se leyo, la respuesta es 409.
 */
@Schema(description = "Reemplazo del resumen clinico minimo de la historia")
public record ActualizarResumenRequest(

		@Schema(description = "Nuevo texto del resumen. Reemplaza al anterior",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El texto del resumen es obligatorio")
		// 2000 es el largo de historia_clinica.resumen: un tope mayor solo cambia donde explota.
		@Size(max = 2000, message = "El resumen no puede superar los 2000 caracteres")
		String texto,

		@Schema(description = "Version de la historia que el operador leyo",
				example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
