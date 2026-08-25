package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja logica de un concepto del catalogo (RN-M06-001).
 *
 * <p>El motivo es obligatorio: sin el, la auditoria no responde por que un concepto dejo de
 * ofrecerse seis meses despues, que es justamente cuando alguien lo pregunta.
 */
@Schema(description = "Motivo de la baja logica de un concepto del catalogo")
public record DeactivateCatalogoRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la fila y en la auditoria",
				example = "Reemplazada por la practica del nomenclador 2026",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
