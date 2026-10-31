package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "DescartarDeListaDeEspera")
public record DescartarDeListaDeEsperaRequest(
		@Schema(description = "Opcional", example = "Ya consiguio turno") @Size(max = 300) String motivo,
		@Schema(example = "0") @NotNull @Min(0) Long expectedVersion) {
}
