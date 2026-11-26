package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Version leida del pedido de cambio de plan")
public record VersionDePedidoRequest(
		@Schema(description = "Version leida", example = "0")
		@NotNull(message = "La versión del pedido es obligatoria") Long version) {
}
