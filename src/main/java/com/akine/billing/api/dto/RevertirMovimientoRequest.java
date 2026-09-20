package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Compensacion de un movimiento anterior (RN-M20-003, RF-M24-006).
 *
 * <p>El motivo es obligatorio y no tiene default. Es la misma decision que la anulacion de una
 * obligacion en 07.01: una plata que se mueve sin explicacion es lo primero que una auditoria
 * busca, y no hay forma de saber despues si fue un error de carga, una cortesia o algo peor.
 */
@Schema(
		name = "RevertirMovimientoDeCaja",
		description = "Compensa un movimiento con otro de signo opuesto. **No borra nada** y la "
				+ "compensacion cae en la jornada abierta hoy, no en la del original.")
public record RevertirMovimientoRequest(

		@Schema(example = "Se cargo el egreso con un cero de mas")
		@NotBlank @Size(max = 280) String motivo) {
}
