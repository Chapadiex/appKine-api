package com.akine.scheduling.application;

import java.math.BigDecimal;

/**
 * El estado del prepago de la recepcion de un turno (AKINE E-6, DP-06 / ADR-0013).
 *
 * <p>Se calcula al leer —la politica de la oferta y el anticipo de billing—, nunca se persiste en
 * la recepcion: una segunda copia del dato divergiria en cuanto alguien anule el anticipo.
 *
 * @param estado          {@link #NO_EXIGIDO}, {@link #PENDIENTE} o {@link #REGISTRADO}
 * @param importeSugerido el precio particular de la oferta, solo cuando el prepago esta pendiente
 *                        y la atencion no se resolvio con cobertura. Es una sugerencia: el
 *                        importe lo decide quien cobra
 * @param moneda          la del precio sugerido o la del anticipo registrado
 * @param cobroId         el anticipo, cuando hay uno vigente
 * @param importe         lo que se cobro como anticipo
 * @param saldoAFavor     lo que el anticipo todavia no imputo ni reintegro
 */
public record PrepagoView(
		String estado,
		BigDecimal importeSugerido,
		String moneda,
		Long cobroId,
		BigDecimal importe,
		BigDecimal saldoAFavor) {

	/** La oferta no exige prepago, o la atencion se resolvio con cobertura. */
	public static final String NO_EXIGIDO = "NO_EXIGIDO";
	/** La oferta lo exige y todavia no hay anticipo: es la ALERTA del mostrador. */
	public static final String PENDIENTE = "PENDIENTE";
	/** Hay un anticipo vigente tomado en esta recepcion. */
	public static final String REGISTRADO = "REGISTRADO";

	public boolean pendiente() {
		return PENDIENTE.equals(estado);
	}
}
