package com.akine.clinical.spi;

import java.time.Instant;
import java.util.List;

/**
 * El plan que ocupa el lugar del caso —ACTIVO o SUSPENDIDO— con su version vigente y su avance
 * (M31).
 *
 * <p>El avance es el mismo que devuelve {@code GET .../planes/{id}/avance}: derivado de las
 * sesiones cerradas del caso, nunca leido de una columna.
 *
 * @param estado              {@code ACTIVO} o {@code SUSPENDIDO}
 * @param versionVigente      numero de la version de contenido vigente
 * @param versionVigenteDesde cuando se registro esa version: lo que permite saber si el plan
 *                            cambio despues de una fecha
 * @param completo            todos los items planificados alcanzaron su cantidad; un plan sin
 *                            items nunca esta completo
 */
public record PlanVigenteSnapshot(
		long planId,
		String estado,
		Instant activadoEn,
		int versionVigente,
		Instant versionVigenteDesde,
		String objetivos,
		String indicaciones,
		Integer frecuenciaSemanal,
		Integer duracionSemanas,
		List<ItemDelPlan> items,
		boolean completo) {

	public PlanVigenteSnapshot {
		items = items == null ? List.of() : List.copyOf(items);
	}

	public boolean activo() {
		return "ACTIVO".equals(estado);
	}

	/** El avance de una oferta del plan. */
	public record ItemDelPlan(
			long ofertaId,
			String ofertaNombre,
			int planificadas,
			int realizadas,
			int canceladas,
			boolean completo) {
	}
}
