package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.knowledge.spi.PasajeRecuperado;

import java.util.Set;

/**
 * Un pasaje de la biblioteca tal como viajo al modelo: id "P1".."P12" y los hallazgos a los que pertenece.
 *
 * <p>{@code pasaje.texto()} es el texto que de verdad viajo (recortado). Si el detector lo marco, {@code omitido}
 * es {@code true}: no viajo, no se puede citar y no aporta numeros. {@link PromptDeSugerencias#armar} devuelve
 * la evidencia ya con esa forma; el validador debe recibir esa y no la original.
 */
public record PasajeEnviado(String id, PasajeRecuperado pasaje, Set<CodigoDeHallazgo> hallazgos, boolean omitido) {

	public PasajeEnviado {
		hallazgos = Set.copyOf(hallazgos);
	}

	public PasajeEnviado(String id, PasajeRecuperado pasaje, Set<CodigoDeHallazgo> hallazgos) {
		this(id, pasaje, hallazgos, false);
	}
}
