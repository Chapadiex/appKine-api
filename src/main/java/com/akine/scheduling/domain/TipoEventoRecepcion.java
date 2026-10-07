package com.akine.scheduling.domain;

/**
 * Que transicion registra un {@link RecepcionEvento}.
 *
 * <p>Existe aparte del estado destino por la misma razon que {@link TipoEventoTurno}:
 * {@code VALIDACION} y {@code PARTICULAR} pueden terminar las dos en {@code VALIDADA}, y son dos
 * hechos distintos —uno lo calculo el servidor, el otro lo decidio el operador—.
 */
public enum TipoEventoRecepcion {

	/** La persona llego (check-in). Unico evento cuyo estado anterior es {@code null}. */
	LLEGADA,

	/** El servidor valido la cobertura y la documentacion: VALIDADA u OBSERVADA. */
	VALIDACION,

	/** El operador decidio atender como Particular, con motivo (RF-M13-005). */
	PARTICULAR,

	/** Paso a la sala de espera. */
	ESPERA,

	/** La llamaron. */
	LLAMADO,

	/** Se anulo un check-in hecho por error. */
	ANULACION,

	/** El turno se cancelo con la recepcion abierta. */
	CIERRE_POR_CANCELACION
}
