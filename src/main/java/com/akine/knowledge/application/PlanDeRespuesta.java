package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.spi.CaminoBusqueda;

import java.util.List;

/** Resultado de preparar una consulta: sin material, o las fuentes y los mensajes para el modelo. */
public record PlanDeRespuesta(
		boolean sinMaterial, CaminoBusqueda camino, List<FuenteDeConsulta> fuentes, List<MensajeChat> mensajes) {

	public PlanDeRespuesta {
		fuentes = List.copyOf(fuentes);
		mensajes = List.copyOf(mensajes);
	}
}
