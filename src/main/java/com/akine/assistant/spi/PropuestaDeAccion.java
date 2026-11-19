package com.akine.assistant.spi;

import java.util.List;
import java.util.Map;

/**
 * Lo que una accion propone. O trae una {@link Tarjeta} para confirmar, o un texto que explica por
 * que no hay nada que proponer ("no hay ninguna clase hoy").
 *
 * @param titulo      el encabezado de la tarjeta
 * @param tarjeta     lo que se va a grabar, editable; {@code null} si {@code sinTarjeta} no lo es
 * @param referencias los ids de lo propuesto ({@link #PERSONA}, {@link #TURNO}): la confirmacion solo
 *                    se acepta si la tarjeta que vuelve habla de lo mismo
 * @param avisos      lo que hay que saber; se puede confirmar igual
 * @param bloqueos    por que no se puede confirmar
 * @param sinTarjeta  el texto de una respuesta sin tarjeta, o {@code null}
 */
public record PropuestaDeAccion(
		String titulo,
		Tarjeta tarjeta,
		Map<String, Long> referencias,
		List<String> avisos,
		List<String> bloqueos,
		String sinTarjeta) {

	public static final String PERSONA = "personaId";
	public static final String TURNO = "turnoId";

	public PropuestaDeAccion {
		referencias = referencias == null ? Map.of() : Map.copyOf(referencias);
		avisos = avisos == null ? List.of() : List.copyOf(avisos);
		bloqueos = bloqueos == null ? List.of() : List.copyOf(bloqueos);
	}

	public static PropuestaDeAccion sinTarjeta(String texto) {
		return new PropuestaDeAccion(null, null, Map.of(), List.of(), List.of(), texto);
	}
}
