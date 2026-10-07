package com.akine.offering.spi;

import java.math.BigDecimal;

/**
 * Lo que cuesta una oferta, para quien tenga que cotizar.
 *
 * <p><b>Es una proyeccion aparte de {@link OfertaSnapshot} a proposito.</b> Aquel excluye todo lo
 * economico porque el motor de agenda no cotiza, y si el precio viajara ahi, cualquier modulo que
 * pida una oferta para saber su duracion se llevaria de arrastre el dato economico. El
 * acoplamiento con el circuito de plata tiene que ser explicito: quien necesita el precio lo pide
 * por su nombre.
 *
 * @param precioBase       {@code null} si la oferta no esta tarifada. M27 lo permite, y no es un
 *                         error: es una prestacion que el centro todavia no puso en lista
 * @param admiteObraSocial si el centro declaro que la oferta se le puede facturar a un financiador
 *                         (M27). Viaja con el precio porque es el otro dato economico de la oferta:
 *                         decide si el devengo busca cobertura o cobra particular (AKINE F-4)
 */
public record PrecioDeOferta(
		long ofertaId, BigDecimal precioBase, String moneda, boolean admiteObraSocial) {

	/** Sin el dato de obra social: la forma anterior a F-4, que nunca factura a un financiador. */
	public PrecioDeOferta(long ofertaId, BigDecimal precioBase, String moneda) {
		this(ofertaId, precioBase, moneda, false);
	}

	public boolean estaTarifada() {
		return precioBase != null && precioBase.signum() > 0;
	}
}
