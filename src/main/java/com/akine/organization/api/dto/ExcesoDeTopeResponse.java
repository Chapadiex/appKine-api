package com.akine.organization.api.dto;

import com.akine.organization.spi.LimitCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un tope del plan pedido que el centro hoy supera")
public record ExcesoDeTopeResponse(
		@Schema(description = "Que se cuenta", example = "MAX_CONSULTORIOS") LimitCode limitCode,
		@Schema(description = "Tope del plan pedido", example = "1") int tope,
		@Schema(description = "Cuantos tiene hoy el centro", example = "3") long uso) {
}
