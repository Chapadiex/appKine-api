package com.akine.clinical.api.dto;

import com.akine.clinical.domain.TipoAntecedente;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de un antecedente clinico en la historia de una persona (RF-M09-002). */
@Schema(description = "Alta de un antecedente clinico")
public record RegistrarAntecedenteRequest(

		@Schema(description = "Clase de antecedente", example = "ALERGIA",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de antecedente es obligatorio")
		TipoAntecedente tipo,

		@Schema(description = "Texto clinico del antecedente",
				example = "Alergia a la penicilina",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La descripcion del antecedente es obligatoria")
		@Size(max = 1000, message = "La descripcion no puede superar los 1000 caracteres")
		String descripcion) {
}
