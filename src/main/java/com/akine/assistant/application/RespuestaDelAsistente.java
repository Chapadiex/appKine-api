package com.akine.assistant.application;

import java.util.List;
import java.util.Map;

/**
 * La respuesta a una pregunta.
 *
 * @param texto          la respuesta: la redacta el agente con los resultados de sus herramientas, o un
 *                       texto fijo en las acciones
 * @param interpretacion deprecada desde el agente: siempre {@code FUERA_DE_ALCANCE}
 * @param filas          los datos detras del texto, para una tabla chica
 * @param opciones       si no esta claro de quien se habla, las personas entre las que elegir
 * @param enlace         ruta del frontend donde ver el detalle, o {@code null}
 * @param advertencias   lo que hay que saber para no leer mal la respuesta
 * @param resultado      como termino la consulta (ver {@link Resultado})
 * @param consulta       el SELECT de una consulta libre, para que el usuario vea de donde sale el
 *                       numero; {@code null} en las demas
 * @param accion         con {@link Resultado#PROPUESTA}, la tarjeta para confirmar
 * @param herramientas   las herramientas que llamo el agente, en orden, para "Ver como lo resolvi"
 */
public record RespuestaDelAsistente(
		String texto,
		Interpretacion interpretacion,
		List<Map<String, Object>> filas,
		List<OpcionDePersona> opciones,
		String enlace,
		List<String> advertencias,
		Resultado resultado,
		String consulta,
		AccionParaConfirmar accion,
		List<LlamadaRegistrada> herramientas) {

	public RespuestaDelAsistente(String texto, Interpretacion interpretacion, List<Map<String, Object>> filas,
			List<OpcionDePersona> opciones, String enlace, List<String> advertencias, Resultado resultado,
			String consulta, AccionParaConfirmar accion) {
		this(texto, interpretacion, filas, opciones, enlace, advertencias, resultado, consulta, accion, List.of());
	}

	public RespuestaDelAsistente(String texto, Interpretacion interpretacion, List<Map<String, Object>> filas,
			List<OpcionDePersona> opciones, String enlace, List<String> advertencias, Resultado resultado,
			String consulta) {
		this(texto, interpretacion, filas, opciones, enlace, advertencias, resultado, consulta, null);
	}

	public RespuestaDelAsistente(String texto, Interpretacion interpretacion, List<Map<String, Object>> filas,
			List<OpcionDePersona> opciones, String enlace, List<String> advertencias, Resultado resultado) {
		this(texto, interpretacion, filas, opciones, enlace, advertencias, resultado, null);
	}

	public RespuestaDelAsistente {
		filas = filas == null ? List.of() : List.copyOf(filas);
		opciones = opciones == null ? List.of() : List.copyOf(opciones);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
		herramientas = herramientas == null ? List.of() : List.copyOf(herramientas);
		interpretacion = interpretacion == null ? Interpretacion.FUERA_DE_ALCANCE : interpretacion;
	}

	/** Como termino la consulta. Viaja a la auditoria y a la respuesta. */
	public enum Resultado {
		RESPONDIDA, ACLARACION, FALTA_PERSONA, PERSONA_NO_ENCONTRADA, SIN_PERMISO, FUERA_DE_ALCANCE,
		/** La frase pedia cargar algo: {@code accion} trae la tarjeta para confirmar. */
		PROPUESTA
	}

	/**
	 * Una accion propuesta, a la espera de la confirmacion (docs/diseno/AKINE-asistente-acciones.md).
	 *
	 * @param id       con lo que se confirma o se descarta
	 * @param venceEn  hasta cuando se puede confirmar
	 * @param avisos   lo que hay que saber; se puede confirmar igual
	 * @param bloqueos por que no se puede confirmar
	 */
	public record AccionParaConfirmar(long id, com.akine.assistant.spi.TipoDeAccion tipo, String titulo,
			java.time.Instant venceEn, com.akine.assistant.spi.Tarjeta tarjeta, List<String> avisos,
			List<String> bloqueos) {
	}

	/** Una persona entre las que elegir: el cliente reenvia la pregunta con su {@code personaId}. */
	public record OpcionDePersona(long personaId, String etiqueta) {
	}
}
