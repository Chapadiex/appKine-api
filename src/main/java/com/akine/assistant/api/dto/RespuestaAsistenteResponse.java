package com.akine.assistant.api.dto;

import com.akine.assistant.application.RespuestaDelAsistente;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** La respuesta del asistente de datos. */
@Schema(description = "La respuesta del asistente de datos")
public record RespuestaAsistenteResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "La respuesta en texto, armada con "
				+ "una plantilla a partir de los datos: el modelo no la redacta") String texto,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) InterpretacionDto interpretacion,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Como termino la consulta",
				allowableValues = {"RESPONDIDA", "ACLARACION", "FALTA_PERSONA", "PERSONA_NO_ENCONTRADA",
						"SIN_PERMISO", "FUERA_DE_ALCANCE", "PROPUESTA"})
		String resultado,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Los datos detras del texto, una fila por elemento. Las claves dependen de la "
						+ "intencion; los instantes van en ISO-8601"),
				schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE))
		List<Map<String, Object>> filas,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si no esta claro de quien se habla, las personas entre las que elegir"))
		List<OpcionDePersonaResponse> opciones,
		@Schema(description = "Ruta del frontend donde ver el detalle", example = "/pacientes/123") String enlace,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
		List<String> advertencias,
		@Schema(description = "El SELECT que respondio una consulta libre (la pregunta no entraba en ninguna "
				+ "intencion y el usuario tiene asistente:sql); null en las demas",
				example = "SELECT count(*) AS pacientes FROM personas WHERE es_paciente AND activa")
		String consulta,
		@Schema(description = "Con `resultado` PROPUESTA: la accion que se propone, para revisarla y confirmarla "
				+ "con `confirmarAccionDelAsistente`. Nada se grabo todavia; null en las consultas")
		AccionPropuestaResponse accion) {

	public static RespuestaAsistenteResponse de(RespuestaDelAsistente r) {
		return new RespuestaAsistenteResponse(r.texto(), InterpretacionDto.de(r.interpretacion()), r.resultado().name(),
				r.filas(), r.opciones().stream().map(o -> new OpcionDePersonaResponse(o.personaId(), o.etiqueta())).toList(),
				r.enlace(), r.advertencias(), r.consulta(), AccionPropuestaResponse.de(r.accion()));
	}

	/** Una persona entre las que elegir. */
	@Schema(name = "OpcionDePersonaDelAsistente", description = "Una persona entre las que elegir")
	public record OpcionDePersonaResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long personaId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Pérez, Juan · DNI ***456") String etiqueta) {
	}
}
