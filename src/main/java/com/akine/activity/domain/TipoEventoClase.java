package com.akine.activity.domain;

/** Que le paso a una clase. Ver {@link ClaseEvento}: el historial es append-only. */
public enum TipoEventoClase {

	CREACION,
	REPROGRAMACION,
	CANCELACION,

	/** La clase abrio y se empezo a tomar lista (AKINE-08.03, RF-M13-007). */
	INICIO,

	/** Cierre operativo: los que quedaron sin marcar pasaron a ausentes (AKINE-08.03). */
	CIERRE
}
