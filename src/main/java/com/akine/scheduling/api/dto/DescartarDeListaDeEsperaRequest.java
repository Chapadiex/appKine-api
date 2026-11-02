package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "DescartarDeListaDeEspera")
public record DescartarDeListaDeEsperaRequest(
		@Schema(description = "Opcional", example = "Ya consiguio turno")
		@Size(max = 300, message = "El motivo no puede superar los 300 caracteres") String motivo,
		@Schema(example = "0")
		@NotNull(message = "La version esperada es obligatoria")
		@Min(value = 0, message = "La version esperada no puede ser negativa") Long expectedVersion) {
}
