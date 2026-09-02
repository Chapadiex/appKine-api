package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la baja logica de una cobertura.
 *
 * <p><b>Dar de baja NO es finalizar la vigencia.</b> Finalizar es "el paciente cambio de obra
 * social" y se hace con el PUT: la cobertura queda ACTIVA y sigue explicando el pasado. Dar de
 * baja es "esta cobertura nunca debio cargarse". Ninguna de las dos borra nada ni toca un hecho ya
 * registrado (RN-M08-003).
 *
 * <p>El motivo es obligatorio: sin el, la auditoria no responde por que seis meses despues.
 */
@Schema(description = "Motivo de la baja logica de la cobertura")
public record DeactivateCoberturaRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la fila y en la auditoria",
				example = "Se cargo con el plan equivocado",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
