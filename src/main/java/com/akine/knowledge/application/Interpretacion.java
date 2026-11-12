package com.akine.knowledge.application;

import java.util.List;

/** Lo que se hace con una consulta: repreguntar o buscar. No se guarda ni se registra. */
public sealed interface Interpretacion {

	/** Repregunta sin generacion; {@code opciones} vacia cuando no hay de donde sacarlas. */
	record Aclarar(String texto, List<OpcionDeAclaracion> opciones) implements Interpretacion {

		public Aclarar {
			opciones = List.copyOf(opciones);
		}
	}

	/**
	 * Busqueda: {@code consultas} son los textos a buscar, {@code independiente} indica que la
	 * consulta no hereda nada de la charla (ni tema, ni historial al modelo que redacta) y
	 * {@code profundo} que pide mas detalle de lo anterior.
	 */
	record Buscar(String tema, Intencion intencion, String preguntaCompleta, List<String> consultas,
			boolean independiente, boolean profundo) implements Interpretacion {

		public Buscar {
			consultas = List.copyOf(consultas);
		}
	}
}
