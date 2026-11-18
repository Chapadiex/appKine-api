package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Descarte de un pedido de prueba")
public record DescartarSolicitudRequest(
		@Schema(description = "Por que se descarta", example = "Pedido duplicado")
		@NotBlank @Size(max = 500) String motivo,
		@Schema(description = "Version leida", example = "0")
		@NotNull Long version) {
}
