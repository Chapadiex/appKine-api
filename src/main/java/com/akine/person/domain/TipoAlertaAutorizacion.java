package com.akine.person.domain;

/**
 * Que clase de alerta pesa sobre una autorizacion (RN-M17-003, DP-13).
 *
 * <p>Solo las alertas que nacen de un <b>hecho de otro modulo</b> viven en una tabla. Las de
 * vencimiento y agote (RF-M17-006) se calculan al leer contra la fecha que se pregunta y no estan
 * aca: materializarlas exigiria un job, y un job que no corre deja alertas viejas.
 */
public enum TipoAlertaAutorizacion {

	/**
	 * Se anulo la obligacion de una sesion que consumio esta autorizacion (DP-13).
	 *
	 * <p><b>No mueve el saldo.</b> Anular la deuda no prueba que la prestacion no ocurrio —puede
	 * ser una cortesia o un error de precio—, asi que la unidad no se devuelve sola: alguien tiene
	 * que mirar y, si corresponde, revertir el consumo a mano (RF-M17-005).
	 */
	CONSUMO_A_REVISAR
}
