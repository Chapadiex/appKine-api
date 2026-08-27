package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la baja logica de un servicio o de una oferta.
 *
 * <p>Un DTO para las dos porque el cuerpo es literalmente el mismo, y porque el motivo es
 * <b>obligatorio</b> en ambas: sin el, la auditoria no responde por que seis meses despues.
 *
 * <p>La baja no borra. Un servicio dado de baja deja de poder ofrecerse en ofertas NUEVAS y
 * sigue resolviendo para las que ya existen (RF-M27-002, RN-M03-006); una oferta dada de baja
 * deja de admitir reservas nuevas y conserva sus historicos (RN-M27-007).
 */
@Schema(description = "Motivo de la baja logica")
public record DeactivateOfferingRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la fila y en la auditoria",
				example = "El centro dejo de prestar el servicio",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
