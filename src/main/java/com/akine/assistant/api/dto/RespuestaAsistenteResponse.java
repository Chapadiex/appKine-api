package com.akine.assistant.api.dto;

import com.akine.assistant.application.RespuestaDelAsistente;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** La respuesta del asistente de datos. */
@Schema(description = "La respuesta del asistente de datos")
public record RespuestaAsistenteResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "La respuesta en texto. La redacta el "
				+ "modelo a partir de los resultados de las herramientas que llamo en esta consulta") String texto,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, deprecated = true, description = "Deprecada desde "
				+ "el agente: siempre FUERA_DE_ALCANCE. Se mantiene por compatibilidad") InterpretacionDto interpretacion,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Como termino la consulta",
				allowableValues = {"RESPONDIDA", "ACLARACION", "FALTA_PERSONA", "PERSONA_NO_ENCONTRADA",
						"SIN_PERMISO", "FUERA_DE_ALCANCE", "PROPUESTA"})
		String resultado,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Los datos detras del texto, una fila por elemento. Las claves dependen de la "
						+ "herramienta; los instantes van en ISO-8601"),
				schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE))
		List<Map<String, Object>> filas,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si no esta claro de quien se habla, las personas entre las que elegir"))
		List<OpcionDePersonaResponse> opciones,
		@Schema(description = "Ruta del frontend donde ver el detalle", example = "/pacientes/123") String enlace,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
		List<String> advertencias,
		@Schema(description = "El ultimo SELECT que corrio la herramienta de consulta en SQL para "
				+ "responder (requiere asistente:sql); null si no se uso",
				example = "SELECT count(*) AS pacientes FROM personas WHERE es_paciente AND activa")
		String consulta,
		@Schema(description = "Con `resultado` PROPUESTA: la accion que se propone, para revisarla y confirmarla "
				+ "con `confirmarAccionDelAsistente`. Nada se grabo todavia; null en las consultas")
		AccionPropuestaResponse accion,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Las herramientas que llamo el asistente para responder, en orden, con su entrada: "
						+ "para mostrar como lo resolvio. Vacia en las acciones"))
		List<LlamadaDeHerramientaResponse> herramientas) {

	public static RespuestaAsistenteResponse de(RespuestaDelAsistente r) {
		return new RespuestaAsistenteResponse(r.texto(), InterpretacionDto.de(r.interpretacion()), r.resultado().name(),
				r.filas(), r.opciones().stream().map(o -> new OpcionDePersonaResponse(o.personaId(), o.etiqueta())).toList(),
				r.enlace(), r.advertencias(), r.consulta(), AccionPropuestaResponse.de(r.accion()),
				r.herramientas().stream().map(h -> new LlamadaDeHerramientaResponse(h.herramienta(), h.entrada(),
						h.sinPermiso(), h.filas())).toList());
	}

	/** Una herramienta que llamo el asistente. */
	@Schema(name = "LlamadaDeHerramientaDelAsistente", description = "Una herramienta que llamo el asistente")
	public record LlamadaDeHerramientaResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "buscar_persona") String herramienta,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "La entrada que le paso el modelo")
			Map<String, Object> entrada,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "El servicio rechazo al usuario")
			boolean sinPermiso,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Cuantas filas devolvio") int filas) {
	}

	/** Una persona entre las que elegir. */
	@Schema(name = "OpcionDePersonaDelAsistente", description = "Una persona entre las que elegir")
	public record OpcionDePersonaResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long personaId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Pérez, Juan · DNI ***456") String etiqueta) {
	}
}
