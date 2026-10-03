package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja LOGICA de un antecedente clinico (regla maestra 10).
 *
 * <p>No borra nada: el antecedente sigue consultable con su motivo. No lleva
 * {@code expectedVersion} porque la baja es idempotente: repetirla devuelve el antecedente como
 * estaba, con su motivo original.
 */
@Schema(description = "Baja logica de un antecedente clinico")
public record BajaDeAntecedenteRequest(

		@Schema(description = "Motivo declarado de la baja. Obligatorio",
				example = "Registrado en la historia equivocada",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo) {
}
