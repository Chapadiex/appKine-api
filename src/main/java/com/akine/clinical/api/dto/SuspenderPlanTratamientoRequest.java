package com.akine.clinical.api.dto;

import com.akine.clinical.domain.PlanTratamiento;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Suspension de un Plan de Tratamiento, con motivo (RF-M11-006).
 *
 * <p><b>Sin motivo, "se freno" es indistinguible de "lo abandonaron"</b> y el historial deja de
 * servir para lo unico que sirve. Se valida aca y otra vez en el dominio: la de aca es comodidad
 * para el formulario, la del dominio es la que vale.
 *
 * <p>Suspender <b>no</b> es finalizar y <b>no</b> libera el lugar del plan activo del Caso: es
 * frenar el que hay. Quien quiera empezar otro tratamiento finaliza este.
 */
@Schema(description = "Suspension de un plan: el tratamiento se discontinua pero no se cierra")
public record SuspenderPlanTratamientoRequest(

		@Schema(description = "Por que se discontinua",
				example = "El paciente viaja por dos meses y retoma a la vuelta",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La suspension de un plan exige un motivo declarado")
		@Size(max = PlanTratamiento.MOTIVO_MAXIMO,
				message = "El motivo no puede superar los 500 caracteres")
		String motivo,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista", example = "4",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
