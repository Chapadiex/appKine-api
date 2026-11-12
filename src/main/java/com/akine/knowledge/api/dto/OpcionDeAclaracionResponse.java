package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.OpcionDeAclaracion;
import io.swagger.v3.oas.annotations.media.Schema;

/** Un boton de la repregunta: lo que se muestra y la pregunta que manda el clic. */
@Schema(description = "Opcion de una repregunta de la biblioteca: el clic manda `pregunta` como una consulta nueva",
		requiredProperties = {"etiqueta", "pregunta"})
public record OpcionDeAclaracionResponse(String etiqueta, @Schema(maxLength = 600) String pregunta) {

	public static OpcionDeAclaracionResponse de(OpcionDeAclaracion o) {
		return new OpcionDeAclaracionResponse(o.etiqueta(), o.pregunta());
	}
}
