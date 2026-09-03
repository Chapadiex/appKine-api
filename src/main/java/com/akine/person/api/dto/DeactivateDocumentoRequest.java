package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la baja logica de una orden o de una autorizacion.
 *
 * <p><b>Dar de baja no es vencer ni rechazar.</b> Un documento vencido sigue vivo y consultable
 * —"un documento vencido no desaparece" es requisito de la etapa— y una autorizacion rechazada es
 * la respuesta del financiador, que tambien queda como historico. Dar de baja significa "esto
 * nunca debio cargarse".
 *
 * <p>El motivo es obligatorio: sin el, la auditoria no responde por que seis meses despues.
 */
@Schema(description = "Motivo de la baja logica del registro")
public record DeactivateDocumentoRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la fila y en la auditoria",
				example = "Se cargo sobre el paciente equivocado",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
