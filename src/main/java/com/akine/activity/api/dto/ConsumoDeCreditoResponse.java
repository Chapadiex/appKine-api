package com.akine.activity.api.dto;

import com.akine.activity.application.ConsumoDeCredito;
import io.swagger.v3.oas.annotations.media.Schema;

/** El efecto de una asistencia sobre los pases de la persona (RF-M29-003, RN-M29-005). */
@Schema(
		name = "ConsumoDeCredito",
		description = "Que le hizo una asistencia al saldo de pases. **Un credito no es plata**: "
				+ "consumirlo no mueve caja ni crea deuda (RN-M29-006, CA-M18-009-06).")
public record ConsumoDeCreditoResponse(

		@Schema(
				description = "`CONSUMIDO`: se desconto un credito. `DEVUELTO`: una correccion a "
						+ "ausente devolvio el credito tomado. `SIN_CAMBIO`: la asistencia ya tenia "
						+ "su credito tomado. `SIN_PASE_ELEGIBLE`: la oferta cobra por pack y la "
						+ "persona no tiene pase vigente con saldo; la asistencia queda registrada "
						+ "igual. `NO_APLICA`: la oferta no cobra por pack.",
				allowableValues = {"CONSUMIDO", "DEVUELTO", "SIN_CAMBIO", "SIN_PASE_ELEGIBLE", "NO_APLICA"},
				example = "CONSUMIDO")
		String efecto,

		@Schema(description = "El pase afectado. Ausente si no hubo movimiento.", example = "77")
		Long paseId,

		@Schema(description = "Saldo del pase despues del movimiento.", example = "7")
		Integer saldo) {

	public static ConsumoDeCreditoResponse de(ConsumoDeCredito consumo) {
		return consumo == null
				? null
				: new ConsumoDeCreditoResponse(consumo.efecto().name(), consumo.paseId(), consumo.saldo());
	}
}
