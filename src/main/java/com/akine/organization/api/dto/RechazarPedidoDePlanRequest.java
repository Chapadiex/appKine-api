package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Rechazo de un pedido de cambio de plan")
public record RechazarPedidoDePlanRequest(
		@Schema(description = "Por que se rechaza; lo ve el centro", example = "El plan Plus requiere convenio anual")
		@NotBlank(message = "Indicá el motivo del rechazo")
		@Size(max = 500, message = "El motivo admite hasta 500 caracteres") String motivo,
		@Schema(description = "Version leida", example = "0")
		@NotNull(message = "La versión del pedido es obligatoria") Long version) {
}
