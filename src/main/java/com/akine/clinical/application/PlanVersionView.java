package com.akine.clinical.application;

import com.akine.clinical.domain.PlanTratamientoVersion;

import java.time.Instant;
import java.util.List;

/**
 * Una version del plan con <b>sus</b> items, tal como sale de la aplicacion.
 *
 * <p>Los items viajan dentro de la version y no al lado del plan, y eso no es un detalle de
 * serializacion: es el modelo. Cada version tiene su propio juego de cantidades, asi que el avance
 * "8 de 20" de la version 1 y el "8 de 24" de la version 2 son dos numeros distintos y los dos
 * correctos. Devolverlos colgando del plan haria imposible decir de cual se esta hablando.
 */
public record PlanVersionView(
		long id,
		int numeroVersion,
		String objetivos,
		String indicaciones,
		Integer frecuenciaSemanal,
		Integer duracionSemanas,
		String motivoModificacion,
		Instant registradaEn,
		long registradaPor,
		List<PlanItemView> items) {

	public static PlanVersionView de(PlanTratamientoVersion version, List<PlanItemView> items) {
		return new PlanVersionView(
				version.getId(),
				version.getNumeroVersion(),
				version.getObjetivos(),
				version.getIndicaciones(),
				version.getFrecuenciaSemanal(),
				version.getDuracionSemanas(),
				version.getMotivoModificacion(),
				version.getRegistradaEn(),
				version.getRegistradaPor(),
				List.copyOf(items));
	}
}
