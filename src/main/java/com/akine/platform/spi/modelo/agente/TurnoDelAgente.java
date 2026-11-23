package com.akine.platform.spi.modelo.agente;

import java.util.List;

/**
 * Una respuesta del modelo: texto y/o llamadas a herramientas.
 *
 * @param crudo contenido nativo del proveedor (en Claude, los bloques con pensamiento), para reenviarlo
 *              sin cambios
 */
public record TurnoDelAgente(String texto, List<LlamadaAHerramienta> llamadas, Object crudo, UsoDelModelo uso) {

	public TurnoDelAgente {
		llamadas = llamadas == null ? List.of() : List.copyOf(llamadas);
	}

	/** {@code true} si el modelo no pidio ninguna herramienta: la respuesta es final. */
	public boolean terminado() {
		return llamadas.isEmpty();
	}
}
