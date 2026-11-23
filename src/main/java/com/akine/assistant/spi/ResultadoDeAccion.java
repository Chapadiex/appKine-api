package com.akine.assistant.spi;

import java.util.List;
import java.util.Map;

/**
 * Como termino una accion confirmada.
 *
 * @param texto       lo que se le dice a la persona
 * @param enlace      ruta del frontend donde ver lo grabado, o {@code null}
 * @param detalles    lo que no salio como se pidio (items de un lote que fallaron)
 * @param referencias los ids de lo creado; van a la fila de la propuesta y a la auditoria
 */
public record ResultadoDeAccion(
		Estado estado,
		String texto,
		String enlace,
		List<String> detalles,
		Map<String, Long> referencias) {

	/** {@code RECHAZADA}: no se grabo nada y la propuesta sigue abierta para corregirla. */
	public enum Estado {
		CONFIRMADA, RECHAZADA
	}

	public ResultadoDeAccion {
		detalles = detalles == null ? List.of() : List.copyOf(detalles);
		referencias = referencias == null ? Map.of() : Map.copyOf(referencias);
	}

	public static ResultadoDeAccion confirmada(String texto, String enlace, Map<String, Long> referencias) {
		return new ResultadoDeAccion(Estado.CONFIRMADA, texto, enlace, List.of(), referencias);
	}

	public static ResultadoDeAccion rechazada(String motivo) {
		return new ResultadoDeAccion(Estado.RECHAZADA, motivo, null, List.of(), Map.of());
	}

	public boolean fueConfirmada() {
		return estado == Estado.CONFIRMADA;
	}
}
