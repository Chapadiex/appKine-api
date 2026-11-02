package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Registra el debito que el financiador informo sobre una prestacion (RF-M21-006).
 *
 * <p><b>Esto no borra la prestacion ni perdona la deuda</b> (RN-M21-004): la obligacion queda
 * pendiente y vuelve a estar disponible para otro lote.
 */
@Schema(
		name = "RegistrarDebito",
		description = "Asienta el rechazo del financiador sobre una prestacion del lote. La "
				+ "sesion original no se toca y la deuda sigue existiendo.")
public record RegistrarDebitoRequest(

		@Schema(
				description = "Lo rechazado. **Decimal exacto, nunca float.** Puede ser menor que "
						+ "lo presentado: un debito parcial es normal.",
				example = "1500.00")
		@NotNull(message = "El importe es obligatorio")
		@DecimalMin(value = "0.01", message = "El importe tiene que ser mayor que cero") BigDecimal importe,

		@Schema(
				description = "Por que lo rechazo. **Obligatorio**: una prestacion que desaparece "
						+ "del reclamo sin explicacion es lo que una auditoria busca.",
				example = "Falta autorizacion previa")
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres") String motivo) {
}
