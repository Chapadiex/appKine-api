package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.PrepagoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** El estado del prepago de una recepcion (AKINE E-6, DP-06 / ADR-0013). */
@Schema(
		name = "PrepagoDeRecepcion",
		description = "Prepago de la recepcion: se calcula al leer con la politica de la oferta y "
				+ "el anticipo registrado en billing. PENDIENTE es una ALERTA: nunca impide pasar "
				+ "a espera, atender ni cerrar la sesion (DP-06).")
public record PrepagoDeRecepcionResponse(

		// CUIDADO: lista escrita a mano, con las constantes de PrepagoView.
		@Schema(
				description = "`NO_EXIGIDO` (la oferta no lo exige, la atencion se resolvio con "
						+ "cobertura o la recepcion esta anulada o cerrada), `PENDIENTE` (lo exige "
						+ "y no hay anticipo) o `REGISTRADO` (hay un anticipo vigente con este turnoId).",
				allowableValues = {PrepagoView.NO_EXIGIDO, PrepagoView.PENDIENTE, PrepagoView.REGISTRADO},
				example = "PENDIENTE")
		String estado,

		@Schema(description = "Precio particular de la oferta, sugerido solo cuando esta PENDIENTE. "
				+ "El importe lo decide quien cobra.", example = "8500.00")
		BigDecimal importeSugerido,

		@Schema(description = "Moneda ISO 4217 del importe sugerido o del anticipo", example = "ARS")
		String moneda,

		@Schema(description = "El anticipo (cobro) registrado, cuando esta REGISTRADO", example = "88")
		Long cobroId,

		@Schema(description = "Lo que se cobro como anticipo", example = "8500.00")
		BigDecimal importe,

		@Schema(description = "Lo que el anticipo todavia no imputo ni reintegro. Al cerrar la "
				+ "sesion se imputa solo a la deuda del paciente", example = "8500.00")
		BigDecimal saldoAFavor) {

	public static PrepagoDeRecepcionResponse de(PrepagoView vista) {
		return vista == null ? null : new PrepagoDeRecepcionResponse(
				vista.estado(), vista.importeSugerido(), vista.moneda(), vista.cobroId(),
				vista.importe(), vista.saldoAFavor());
	}
}
