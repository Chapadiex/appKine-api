package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Cobro de la suscripcion que registra la plataforma")
public record RegistrarCobroRequest(
		@Schema(description = "Meses que se cobran, de 1 a 12", example = "1")
		@NotNull(message = "Los meses son obligatorios")
		@Min(value = 1, message = "Los meses van de 1 a 12")
		@Max(value = 12, message = "Los meses van de 1 a 12")
		Integer meses,
		@Schema(description = "Nota libre: medio de pago, comprobante. Hasta 200 caracteres",
				example = "Transferencia del 19/11")
		@Size(max = 200, message = "La nota admite hasta 200 caracteres")
		String nota) {
}
