package com.akine.billing.application;

import com.akine.billing.domain.MedioDePago;

import java.math.BigDecimal;

/**
 * Devolver en dinero parte del saldo a favor de un cobro (F-3).
 *
 * @param referencia     numero de operacion o lo que el operador anote, como en un medio de cobro
 * @param motivo         obligatorio: plata que sale sin explicacion es lo que una auditoria busca
 * @param idempotencyKey {@code null} la desactiva
 */
public record ReintegroCommand(
		BigDecimal importe,
		MedioDePago medio,
		String referencia,
		String motivo,
		String idempotencyKey) {

	/** Mismo criterio que {@link CobroCommand#huella}: importes normalizados, sin la clave. */
	public String huella(long consultorioId, long cobroId) {
		return Huella.de(consultorioId + "|" + cobroId + "|"
				+ importe.stripTrailingZeros().toPlainString() + "|" + medio.name());
	}
}
