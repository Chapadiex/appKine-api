package com.akine.clinical.application;

import com.akine.clinical.domain.PlanItem;

/**
 * Una practica planificada, tal como sale de la aplicacion.
 *
 * <p>Lleva el snapshot —{@link #ofertaNombre} y {@link #servicioId}— y no solo el id: un plan de
 * seis meses se lee con los nombres que tenia al planificarse, no con los de hoy.
 *
 * <p><b>No lleva realizadas ni canceladas.</b> Eso es el <b>avance</b>, que es otra consulta
 * —{@link AvanceDeItemView}— y se deriva al leer. Mezclarlos aca obligaria a consultar
 * {@code encounter} cada vez que alguien abre la ficha de un plan, y haria que la ficha y el avance
 * pudieran contestar cosas distintas sin que se note cual es cual.
 */
public record PlanItemView(
		long id,
		long ofertaId,
		long ofertaConsultorioId,
		String ofertaNombre,
		long servicioId,
		int cantidadPlanificada,
		Integer cantidadAutorizada,
		String origenAutorizacion,
		Long autorizacionId) {

	public static PlanItemView de(PlanItem item) {
		return new PlanItemView(
				item.getId(),
				item.getOfertaId(),
				item.getOfertaConsultorioId(),
				item.getOfertaNombre(),
				item.getServicioId(),
				item.getCantidadPlanificada(),
				item.getCantidadAutorizada(),
				item.getOrigenAutorizacion().name(),
				item.getAutorizacionId());
	}
}
