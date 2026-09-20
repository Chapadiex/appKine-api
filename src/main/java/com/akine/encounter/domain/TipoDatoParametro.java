package com.akine.encounter.domain;

/**
 * De que tipo es el valor de un parametro de tratamiento.
 *
 * <p><b>Es el nucleo de la etapa, no una clasificacion decorativa.</b> El plan de implementacion
 * nombra como caso borde el "parametro legado sin tipo/unidad que debe rechazarse o normalizarse
 * explicitamente", y este enum mas el {@code CHECK} de V55 son lo que convierte ese rechazo en
 * algo que hace el motor y no la disciplina de quien escriba el proximo endpoint.
 *
 * <p>Una columna {@code json} no podria: la validacion quedaria en Java y la base aceptaria
 * cualquier forma. Ademas MySQL <b>normaliza</b> el json al guardarlo —reordena claves y reescribe
 * numeros—, que es la causa raiz del outbox clavado, y un valor de dosificacion clinica que vuelve
 * del motor con otra representacion es exactamente lo que no se quiere.
 */
public enum TipoDatoParametro {

	/** Intensidad, frecuencia, series, repeticiones, carga. Va en {@code valor_numerico}. */
	NUMERICO,

	/** Maniobra, modo de aplicacion, respuesta cualitativa. Va en {@code valor_texto}. */
	TEXTO,

	/** Con asistencia, con supervision. Va en {@code valor_booleano}. */
	BOOLEANO
}
