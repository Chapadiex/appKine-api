package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Alta de una ocupacion propia del centro")
public record AltaDeOcupacionRequest(

		@Schema(description = "Nombre. Si ya existe (sin importar mayusculas ni tildes) se devuelve esa",
				example = "Guardavidas", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La ocupacion necesita un nombre")
		@Size(max = 80, message = "El nombre de la ocupacion no puede superar los 80 caracteres")
		String nombre) {
}
