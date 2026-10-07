package com.akine.offering.api.dto;

import com.akine.offering.application.OfertaPrecioParticularView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un precio particular de la oferta (B-3, RF-M16-009). */
@Schema(description = "Precio particular de la oferta para un periodo")
public record PrecioParticularResponse(

		@Schema(description = "Identificador del precio", example = "51")
		long id,

		@Schema(description = "Oferta", example = "34")
		long ofertaId,

		@Schema(description = "Precio particular en el periodo", example = "8500.00")
		BigDecimal importe,

		@Schema(description = "ISO 4217", example = "ARS")
		String moneda,

		@Schema(description = "Primer dia en que rige", example = "2026-11-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que rige, INCLUSIVE. Null = sin fin previsto")
		LocalDate vigenciaHasta,

		@Schema(description = "Si es el precio que rige hoy")
		boolean vigente,

		@Schema(description = "Ciclo de vida", allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante de la baja. Null mientras este activo")
		Instant deletedAt,

		@Schema(description = "Motivo de la baja. Null mientras este activo")
		String deactivationReason,

		@Schema(description = "Version a reenviar para cambiar el fin", example = "0")
		long version) {

	public static PrecioParticularResponse de(OfertaPrecioParticularView v) {
		return new PrecioParticularResponse(v.id(), v.ofertaId(), v.importe(), v.moneda(),
				v.vigenciaDesde(), v.vigenciaHasta(), v.vigente(), v.estado(), v.deletedAt(),
				v.deactivationReason(), v.version());
	}
}
