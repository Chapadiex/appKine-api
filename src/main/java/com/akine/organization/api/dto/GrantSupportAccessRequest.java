package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pedido de acceso de soporte sobre un tenant.
 *
 * <p><b>El motivo es obligatorio y el tope de duracion no es negociable.</b> Sin motivo no hay
 * soporte: hay acceso a datos de salud ajenos. Y dejar que quien pide el acceso elija su propia
 * duracion convertiria "acotado en tiempo" en "acotado si el que entra quiere".
 */
@Schema(description = "Acceso de soporte a conceder sobre una organizacion")
public record GrantSupportAccessRequest(

		@Schema(
				description = "Por que se necesita entrar al tenant. Queda en la auditoria del "
						+ "cliente, no solo en la de plataforma",
				example = "Incidente 4821: la caja no cierra y el cliente pidio asistencia",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason,

		@Schema(
				description = "Duracion en minutos. Por defecto y como maximo 240 (cuatro horas)",
				example = "60",
				defaultValue = "240",
				minimum = "1",
				maximum = "240",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "La duracion debe ser de al menos un minuto")
		@Max(value = 240, message = "La duracion no puede exceder las cuatro horas")
		Integer durationMinutes) {
}
