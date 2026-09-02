package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la baja logica de un financiador o de un plan de cobertura.
 *
 * <p>Un DTO para las dos porque el cuerpo es literalmente el mismo, y porque el motivo es
 * <b>obligatorio</b> en ambas: sin el, la auditoria no responde por que seis meses despues.
 *
 * <p>La baja no borra (RN-M15-003). Un financiador dado de baja deja de admitir planes NUEVOS y
 * sus planes dejan de ofrecerse para selecciones nuevas; un plan dado de baja deja de ofrecerse y
 * sus coberturas ya firmadas siguen resolviendo con la copia congelada que guardaron.
 */
@Schema(description = "Motivo de la baja logica")
public record DeactivateContractingRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la fila y en la auditoria",
				example = "El centro dejo de trabajar con este financiador",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
