package com.akine.assistant.application;

import java.util.Map;

/**
 * Una herramienta que el agente llamo para responder, con la entrada que le paso el modelo. Viaja en
 * la respuesta para "Ver como lo resolvi"; a la auditoria va solo el nombre.
 *
 * @param sinPermiso {@code true} si el servicio dueno del dato rechazo al actor
 * @param filas      cuantas filas devolvio
 */
public record LlamadaRegistrada(String herramienta, Map<String, Object> entrada, boolean sinPermiso, int filas) {

	public LlamadaRegistrada {
		entrada = entrada == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(entrada));
	}
}
