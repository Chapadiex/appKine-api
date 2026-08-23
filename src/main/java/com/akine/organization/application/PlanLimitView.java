package com.akine.organization.application;

import com.akine.organization.spi.LimitCode;

/**
 * Un limite del plan, tal como se muestra en el catalogo.
 *
 * @param value tope. {@code null} = ilimitado. Que el limite APAREZCA con {@code null} no es
 *              lo mismo que no aparecer: presente y nulo significa "sin tope, decidido";
 *              ausente significa "este limite no aplica a este plan"
 */
public record PlanLimitView(LimitCode code, Integer value) {

	public boolean unlimited() {
		return value == null;
	}
}
