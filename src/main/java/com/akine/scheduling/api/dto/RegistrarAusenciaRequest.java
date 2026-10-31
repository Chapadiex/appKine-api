package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien registra que el paciente no vino.
 *
 * <p>El motivo es <b>opcional</b>, a diferencia de la cancelacion: quien no vino no siempre avisa
 * por que, y obligar al recepcionista a escribir algo produce un dato inventado.
 */
@Schema(
		name = "RegistrarAusencia",
		description = "Registro de no asistencia. NO libera el lugar y no altera ningun otro turno "
				+ "(DP-04).")
public record RegistrarAusenciaRequest(

		@Schema(description = "Opcional.", example = "No aviso")
		@Size(max = 300)
		String motivo,

		@Schema(
				description = "Si la ausencia consume una sesion del plan del turno. Se ignora si el "
						+ "turno no tiene plan. Omitirlo es que no consume.",
				example = "false")
		Boolean consumeSesionDelPlan,

		@Schema(description = "Version que devolvio la ultima lectura del turno.", example = "1")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
