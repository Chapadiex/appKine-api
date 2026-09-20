package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * El pedido de compensar un consumo (RF-M17-005).
 *
 * <p><b>Esto no borra nada.</b> Crea un movimiento {@code REVERSION} que devuelve la unidad; el
 * consumo original queda donde estaba, diciendo que ocurrio. Regla maestra 10.
 *
 * <p>{@code movimientoId} y no "el ultimo": revertir el consumo equivocado es peor que no revertir
 * ninguno, y "el ultimo" deja de significar lo mismo en cuanto dos cierres del mismo dia se
 * ordenan distinto de lo que el operador recuerda.
 */
@Schema(description = "Reversion de un consumo de autorizacion")
public record RevertirConsumoRequest(

		@Schema(
				description = "Movimiento de CONSUMO que se quiere compensar. Tiene que ser de "
						+ "esta autorizacion",
				example = "9001",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@Positive(message = "La reversion necesita el movimiento que compensa")
		long movimientoId,

		@Schema(
				description = "Por que se revierte. OBLIGATORIO: sin motivo, quien audite no "
						+ "puede distinguir un error de carga de un fraude. Su ausencia es 400, "
						+ "no 409: no hay ningun estado que impida la operacion, falta un dato",
				example = "La sesion se cerro por error sobre el paciente equivocado",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Revertir un consumo exige un motivo declarado")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo) {
}
