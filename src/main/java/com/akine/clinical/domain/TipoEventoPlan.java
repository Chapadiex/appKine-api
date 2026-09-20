package com.akine.clinical.domain;

/**
 * Las transiciones que quedan asentadas en el historial de un Plan de Tratamiento (RF-M11-007).
 *
 * <p>El historial es <b>append-only</b>: un historial que se puede editar no es un historial.
 * Mismo criterio que {@link TipoEventoCaso} y que {@code turno_evento} en 05.03.
 */
public enum TipoEventoPlan {

	/** El plan se creo, en BORRADOR. Es el unico evento sin estado anterior. */
	CREACION,

	/**
	 * Se edito el contenido de un plan en BORRADOR, <b>sin</b> crear version.
	 *
	 * <p>Existe para que el historial no mienta por omision: un plan que llega a ACTIVO con la
	 * version 1 muy distinta de como nacio tuvo ediciones, y no dejarlas asentadas haria parecer
	 * que se escribio de una sola vez.
	 */
	EDICION,

	/** El plan paso a ACTIVO. Si habia otro activo en el Caso, esa activacion lo finalizo. */
	ACTIVACION,

	/**
	 * Se modifico un plan vigente y eso <b>creo una version nueva</b> (RF-M11-004).
	 *
	 * <p>Exige motivo, por lo mismo que la enmienda de una entrada clinica: sin el, una
	 * modificacion es indistinguible de una correccion de tipeo.
	 */
	MODIFICACION,

	/** El tratamiento se discontinuo. <b>Exige motivo.</b> */
	SUSPENSION,

	/** El tratamiento se retomo. El plan vuelve a ACTIVO. */
	REANUDACION,

	/**
	 * El plan termino. Exige motivo.
	 *
	 * <p>Tambien lo escribe la activacion de OTRO plan del mismo Caso, con el motivo que lo
	 * explica: la finalizacion automatica no puede quedar sin rastro, porque quien lee el historial
	 * despues tiene que poder ver por que este plan dejo de estar vigente sin que nadie lo cerrara.
	 */
	FINALIZACION
}
