package com.akine.clinical.domain;

/**
 * De donde salio la cantidad autorizada de un {@link PlanItem} (RF-M11-003).
 *
 * <p>La columna y este enum llegan en 04.04 aunque <b>solo se escriba {@link #DECLARADA}</b>.
 * Agregarlos despues obligaria a decidir que valor llevan las filas viejas, y ninguna respuesta es
 * buena: {@code DECLARADA} mentiria sobre las que vinieran de una autorizacion real y {@code NULL}
 * romperia el CHECK. Es DP-10 literal: se corta el <b>alcance</b>, no el <b>modelo</b>.
 */
public enum OrigenCantidadAutorizada {

	/**
	 * La escribio a mano quien planifico, mirando el carnet o el papel del financiador.
	 *
	 * <p>Es el unico valor que esta etapa produce. No es un dato menos valido: es un dato cuya
	 * fuente es una persona, y el sistema lo registra como tal en vez de fingir que lo verifico.
	 */
	DECLARADA,

	/**
	 * Sale de una {@code Autorizacion} de M17. <b>Todavia no se escribe.</b>
	 *
	 * <p>La costura real es <b>04.05</b> —"Integracion y consumo de autorizaciones"—, que es la
	 * etapa que estrena {@code consultarElegibilidadAdministrativa}, hoy sin un solo consumidor.
	 * Conectarlo aca seria adelantar media integracion y dejar dos caminos que despues hay que
	 * unificar.
	 */
	AUTORIZACION
}
