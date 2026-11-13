package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import com.akine.knowledge.spi.CaminoBusqueda;

import java.util.List;

/**
 * Resultado de preparar una consulta: una repregunta, sin material, o las fuentes y los mensajes
 * para el modelo con sus opciones (breve o profundo). Lleva los tiempos de interpretar y buscar
 * para el log y las metricas ({@code msBuscar} -1 si no se busco); nunca texto de la consulta.
 *
 * @param aclaracion la repregunta, o {@code null}; con ella no hay fuentes ni mensajes
 * @param opciones parametros de la generacion; {@code null} si no se genera
 */
public record PlanDeRespuesta(
		boolean sinMaterial, CaminoBusqueda camino, List<FuenteDeConsulta> fuentes, List<MensajeChat> mensajes,
		Interpretacion.Aclarar aclaracion, OpcionesGeneracion opciones, long msInterpretar, long msBuscar) {

	public PlanDeRespuesta {
		fuentes = List.copyOf(fuentes);
		mensajes = List.copyOf(mensajes);
	}

	public boolean esAclaracion() {
		return aclaracion != null;
	}
}
