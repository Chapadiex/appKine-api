package com.akine.clinical.api.dto;

import com.akine.clinical.application.OcupacionService.OcupacionView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ocupacion del catalogo de la ficha de ingreso")
public record OcupacionResponse(

		@Schema(description = "Identificador", example = "17", requiredMode = Schema.RequiredMode.REQUIRED)
		long id,

		@Schema(description = "Nombre", example = "Docente", requiredMode = Schema.RequiredMode.REQUIRED)
		String nombre,

		@Schema(description = "true si la agrego este centro; false si es del producto",
				requiredMode = Schema.RequiredMode.REQUIRED)
		boolean propia) {

	public static OcupacionResponse from(OcupacionView ocupacion) {
		return new OcupacionResponse(ocupacion.id(), ocupacion.nombre(), ocupacion.propia());
	}
}
