package com.akine.contracting.spi;

import java.math.BigDecimal;

/**
 * La única fórmula del coseguro (docs/diseno/AKINE-obras-sociales-simple.md §5).
 *
 * <pre>
 *   A = precio de la oferta el día de la prestación
 *   B = lo que paga el financiador (importe del arancel)
 *   coseguro = max(A - B, 0)
 * </pre>
 *
 * <p>Sin A (la oferta no tiene precio ese día) el coseguro es 0: con cobertura no se exige precio
 * particular y la sesión no puede quedar sin cerrar por eso. Los usan el devengo, el simulador de
 * arancel efectivo y la cobertura aplicable, para que los tres digan lo mismo.
 */
public final class Coseguro {

	private Coseguro() {
	}

	public static BigDecimal de(BigDecimal precioDeLaOferta, BigDecimal importeFinanciador) {
		if (precioDeLaOferta == null || importeFinanciador == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal diferencia = precioDeLaOferta.subtract(importeFinanciador);
		return diferencia.signum() > 0 ? diferencia : BigDecimal.ZERO;
	}
}
