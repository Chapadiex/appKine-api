package com.akine.assistant.application;

import java.util.List;
import java.util.Map;

/**
 * La respuesta a una pregunta.
 *
 * @param texto          la respuesta armada con la plantilla de la intencion
 * @param interpretacion lo que se entendio; el cliente la devuelve en el historial del siguiente turno
 * @param filas          los datos detras del texto, para una tabla chica
 * @param opciones       si no esta claro de quien se habla, las personas entre las que elegir
 * @param enlace         ruta del frontend donde ver el detalle, o {@code null}
 * @param advertencias   lo que hay que saber para no leer mal la respuesta
 * @param resultado      como termino la consulta (ver {@link Resultado})
 * @param consulta       el SELECT de una consulta libre, para que el usuario vea de donde sale el
 *                       numero; {@code null} en las intenciones del catalogo
 */
public record RespuestaDelAsistente(
		String texto,
		Interpretacion interpretacion,
		List<Map<String, Object>> filas,
		List<OpcionDePersona> opciones,
		String enlace,
		List<String> advertencias,
		Resultado resultado,
		String consulta) {

	public RespuestaDelAsistente(String texto, Interpretacion interpretacion, List<Map<String, Object>> filas,
			List<OpcionDePersona> opciones, String enlace, List<String> advertencias, Resultado resultado) {
		this(texto, interpretacion, filas, opciones, enlace, advertencias, resultado, null);
	}

	public RespuestaDelAsistente {
		filas = filas == null ? List.of() : List.copyOf(filas);
		opciones = opciones == null ? List.of() : List.copyOf(opciones);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
	}

	/** Como termino la consulta. Viaja a la auditoria y a la respuesta. */
	public enum Resultado {
		RESPONDIDA, ACLARACION, FALTA_PERSONA, PERSONA_NO_ENCONTRADA, SIN_PERMISO, FUERA_DE_ALCANCE
	}

	/** Una persona entre las que elegir: el cliente reenvia la pregunta con su {@code personaId}. */
	public record OpcionDePersona(long personaId, String etiqueta) {
	}
}
