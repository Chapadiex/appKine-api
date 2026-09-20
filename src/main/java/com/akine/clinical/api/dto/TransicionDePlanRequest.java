package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de una transicion que <b>no</b> exige motivo: la activacion y la reanudacion.
 *
 * <p><b>Un solo record para las dos, y no para las cuatro.</b> Las que exigen motivo tienen el suyo
 * —{@link SuspenderPlanTratamientoRequest} y {@link FinalizarPlanTratamientoRequest}— porque ahi el
 * motivo es obligatorio y el schema publicado tiene que decirlo. Fusionar las cuatro en un record
 * con {@code motivo} opcional publicaria un contrato que miente sobre dos de ellas, y la primera
 * pantalla que lo lea va a mandar una suspension sin motivo y comerse un 400.
 *
 * <p>Fusionar solo estas dos si es honesto: piden exactamente lo mismo, y separarlas obligaria a
 * mantener dos schemas identicos que van a divergir por descuido.
 */
@Schema(name = "TransicionDePlan",
		description = "Transicion de estado de un plan que no exige motivo")
public record TransicionDePlanRequest(

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista", example = "4",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
