package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Alta de un precio particular de la oferta con vigencia (B-3, RF-M16-009). */
@Schema(description = "Precio particular de la oferta para un periodo")
public record CreatePrecioParticularRequest(

		@Schema(description = "Precio particular en ese periodo. Cero es valido", example = "8500.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El importe es obligatorio")
		@DecimalMin(value = "0.00", message = "El importe no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "El importe admite 2 decimales")
		BigDecimal importe,

		@Schema(description = "ISO 4217. Si se omite, la del precio de lista de la oferta",
				example = "ARS", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Pattern(regexp = "[A-Za-z]{3}", message = "La moneda es un codigo ISO 4217 de 3 letras")
		String moneda,

		@Schema(description = "Primer dia en que rige", example = "2026-11-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La vigencia necesita una fecha de inicio")
		LocalDate vigenciaDesde,

		@Schema(description = "ULTIMO dia en que rige, INCLUSIVE. Null = sin fin previsto",
				example = "2027-03-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta) {
}
