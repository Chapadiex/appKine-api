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
 * @param precioBase {@code null} si la oferta no esta tarifada. M27 lo permite, y no es un error:
 *                   es una prestacion que el centro todavia no puso en lista
 */
public record PrecioDeOferta(long ofertaId, BigDecimal precioBase, String moneda) {

	public boolean estaTarifada() {
		return precioBase != null && precioBase.signum() > 0;
	}
}
