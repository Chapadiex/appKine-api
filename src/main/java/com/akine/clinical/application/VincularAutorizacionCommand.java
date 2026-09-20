package com.akine.clinical.application;

/**
 * Lo que hace falta para atar un item del plan a una autorizacion real (RF-M11-007).
 *
 * <p>Es el hecho que convierte la {@code cantidadAutorizada} <b>declarada</b> de 04.04 —la que
 * escribio a mano quien planifico, mirando el carnet— en una referencia verificable contra M17.
 *
 * <p><b>No lleva {@code cantidadAutorizada}</b>, y esa ausencia es la decision: la cantidad sale
 * de la autorizacion, no del pedido. Aceptarla del cliente permitiria declarar diez donde el
 * financiador otorgo seis, que es exactamente el dato sin fuente que este vinculo existe para
 * reemplazar.
 *
 * @param expectedVersion version del PLAN que el cliente leyo. El vinculo no crea version nueva,
 *                        pero si toca la version vigente, asi que dos operadores que abrieron la
 *                        misma pantalla no pueden pisarse
 */
public record VincularAutorizacionCommand(
		long planItemId, long autorizacionId, long expectedVersion) {
}
