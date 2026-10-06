package com.akine.billing.application;

import com.akine.billing.domain.CobroReintegro;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un reintegro de saldo a favor, con lo que le queda al cobro.
 *
 * @param saldoAFavorRestante lo que el cobro todavia tiene a favor despues de este reintegro. En un
 *                            reintento idempotente es el saldo de HOY, no el de entonces
 */
public record ReintegroView(
		long id,
		long cobroId,
		long consultorioId,
		long personaId,
		BigDecimal importe,
		String moneda,
		String medio,
		String referencia,
		String motivo,
		Instant reintegradoEn,
		BigDecimal saldoAFavorRestante) {

	public static ReintegroView de(CobroReintegro reintegro, BigDecimal saldoAFavorRestante) {
		return new ReintegroView(
				reintegro.getId(),
				reintegro.getCobroId(),
				reintegro.getConsultorioId(),
				reintegro.getPersonaId(),
				reintegro.getImporte(),
				reintegro.getMoneda(),
				reintegro.getMedio().name(),
				reintegro.getReferencia(),
				reintegro.getMotivo(),
				reintegro.getReintegradoEn(),
				saldoAFavorRestante);
	}
}
