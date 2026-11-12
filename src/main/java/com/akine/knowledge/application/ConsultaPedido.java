package com.akine.knowledge.application;

import java.util.List;

/**
 * Pregunta nueva mas el historial que manda el cliente (no confiable). {@code librosPreferidos}
 * son los libros de las fuentes de la respuesta anterior: solo reordenan dentro de la organizacion
 * y solo se usan si la consulta continua la charla (no es independiente).
 */
public record ConsultaPedido(String pregunta, List<TurnoDeConsulta> historial, List<Long> librosPreferidos) {

	public ConsultaPedido(String pregunta, List<TurnoDeConsulta> historial) {
		this(pregunta, historial, null);
	}
}
