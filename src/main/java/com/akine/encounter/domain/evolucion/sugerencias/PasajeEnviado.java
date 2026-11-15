package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.knowledge.spi.PasajeRecuperado;

import java.util.Set;

/** Un pasaje de la biblioteca tal como viajo al modelo: id "P1".."P12" y los hallazgos a los que pertenece. */
public record PasajeEnviado(String id, PasajeRecuperado pasaje, Set<CodigoDeHallazgo> hallazgos) {

	public PasajeEnviado {
		hallazgos = Set.copyOf(hallazgos);
	}
}
