package com.akine.encounter.domain.evolucion.sugerencias;

import java.util.List;
import java.util.Map;

/** Lo aceptado y cuantas se descartaron por cada regla. */
public record ResultadoDeValidacion(List<SugerenciaPropuesta> aceptadas, Map<ReglaDeDescarte, Integer> descartes) {

	public ResultadoDeValidacion {
		aceptadas = List.copyOf(aceptadas);
		descartes = Map.copyOf(descartes);
	}

	public int descartadas() {
		return descartes.values().stream().mapToInt(Integer::intValue).sum();
	}
}
