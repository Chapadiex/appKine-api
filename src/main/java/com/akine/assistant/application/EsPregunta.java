package com.akine.assistant.application;

import com.akine.platform.spi.listado.TextoDeBusqueda;

import java.util.Set;

/**
 * Si una frase pregunta algo en vez de contar o pedir que se cargue algo
 * (docs/diseno/AKINE-asistente-acciones.md, §5.2). Una pregunta va al interpretador de consultas sin
 * pasar por el de acciones: asi las consultas medidas no cambian.
 *
 * <p>Regla del backend y no del modelo: cada cosa que se le pidio decidir al modelo de 4B movio otras
 * preguntas medidas.
 */
final class EsPregunta {

	/** Arranques de consulta, plegados. "vino" no esta: "vino Juan e hizo ultrasonido" cuenta algo. */
	private static final Set<String> ARRANQUES = Set.of(
			"quien", "quienes", "cuanto", "cuanta", "cuantos", "cuantas", "cual", "cuales", "que", "como",
			"cuando", "donde", "hay", "tiene", "tienen", "dame", "decime", "mostrame", "listame", "lista",
			"listado", "turnos", "agenda", "reporte");

	private EsPregunta() {
	}

	static boolean es(String frase) {
		if (frase == null || frase.isBlank()) {
			return false;
		}
		if (frase.indexOf('?') >= 0 || frase.indexOf('¿') >= 0) {
			return true;
		}
		String primera = TextoDeBusqueda.plegar(frase).strip().split("[^\\p{L}\\p{N}]+", 2)[0];
		return ARRANQUES.contains(primera);
	}
}
