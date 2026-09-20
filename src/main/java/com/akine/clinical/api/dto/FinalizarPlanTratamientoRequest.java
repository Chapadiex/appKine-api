package com.akine.clinical.api.dto;

import com.akine.clinical.domain.PlanTratamiento;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Finalizacion de un Plan de Tratamiento, con motivo (RF-M11-006).
 *
 * <p>Es terminal: no se reabre, se crea un plan nuevo. Y es lo que <b>libera el lugar del plan
 * activo</b> del Caso.
 *
 * <p><b>Finalizar no cierra el Caso</b>, aunque sea la tentacion obvia: el Caso puede seguir
 * abierto con otro plan, o sin ninguno mientras se decide el siguiente. Son dos maquinas de estado
 * distintas y ninguna manda sobre la otra.
 */
@Schema(description = "Finalizacion de un plan. Es terminal: no se reabre")
public record FinalizarPlanTratamientoRequest(

		@Schema(description = "Por que termina", example = "Alta por objetivos cumplidos",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La finalizacion de un plan exige un motivo declarado")
		@Size(max = PlanTratamiento.MOTIVO_MAXIMO,
				message = "El motivo no puede superar los 500 caracteres")
		String motivo,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista", example = "5",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
