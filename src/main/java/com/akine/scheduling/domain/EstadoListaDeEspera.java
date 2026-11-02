package com.akine.scheduling.domain;

/** Ciclo de una entrada de la lista de espera. {@code ASIGNADA} y {@code DESCARTADA} son finales. */
public enum EstadoListaDeEspera {

	ACTIVA,
	ASIGNADA,
	DESCARTADA
}
