package com.akine.billing.application;

import java.math.BigDecimal;

/**
 * Aplicar parte del saldo a favor de un cobro a una deuda (F-3, RF-M19-003).
 *
 * <p>Una deuda por pedido, y no una lista: asi la clave de idempotencia es de la fila de
 * imputacion y su unique la sostiene sola.
 *
 * @param idempotencyKey {@code null} la desactiva
 */
public record ImputacionPosteriorCommand(long obligacionId, BigDecimal importe, String idempotencyKey) {

	/** Mismo criterio que {@link CobroCommand#huella}: importes normalizados, sin la clave. */
	public String huella(long consultorioId, long cobroId) {
		return Huella.de(consultorioId + "|" + cobroId + "|" + obligacionId + "|"
				+ importe.stripTrailingZeros().toPlainString());
	}
}
