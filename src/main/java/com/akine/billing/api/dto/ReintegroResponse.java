package com.akine.billing.api.dto;

import com.akine.billing.application.ReintegroView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/** Un reintegro de saldo a favor, con lo que le queda al cobro. */
@Schema(
		name = "ReintegroDeCobro",
		description = "Devolucion en dinero de un saldo a favor. Es una salida de caja de origen "
				+ "REINTEGRO, no una reversion del cobro.")
public record ReintegroResponse(

		@Schema(example = "301")
		long id,

		@Schema(example = "5001")
		long cobroId,

		@Schema(example = "7")
		long consultorioId,

		@Schema(example = "128")
		long personaId,

		@Schema(example = "1500.00")
		BigDecimal importe,

		@Schema(example = "ARS")
		String moneda,

		@Schema(allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"})
		String medio,

		String referencia,

		String motivo,

		@Schema(example = "2026-10-06T15:20:00Z")
		Instant reintegradoEn,

		@Schema(
				description = "Lo que el cobro todavia tiene a favor. En un reintento idempotente es "
						+ "el saldo actual, no el de entonces.",
				example = "2500.00")
		BigDecimal saldoAFavorRestante) {

	public static ReintegroResponse de(ReintegroView vista) {
		return new ReintegroResponse(
				vista.id(), vista.cobroId(), vista.consultorioId(), vista.personaId(),
				vista.importe(), vista.moneda(), vista.medio(), vista.referencia(), vista.motivo(),
				vista.reintegradoEn(), vista.saldoAFavorRestante());
	}
}
