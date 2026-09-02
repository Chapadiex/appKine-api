package com.akine.person.application;

/**
 * Filtro por CICLO DE VIDA del historial de coberturas. No filtra por vigencia.
 *
 * <p>Son dos cosas distintas y la pantalla necesita las dos: una cobertura ACTIVA con la vigencia
 * cerrada es el caso normal de un paciente que cambio de obra social, y colapsarlas dejaria sin
 * poder explicar por que esa cobertura no se ofrece hoy. Mismo criterio que
 * {@code contracting.application.EstadoFiltro}.
 */
public enum CoberturaEstadoFiltro {
	ACTIVA,
	INACTIVA,
	TODAS
}
