package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.Interpretacion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Evento SSE {@code aclaracion}: la consulta no dice que se busca; repregunta sin generar. */
@Schema(description = "Evento `aclaracion` del stream de consultarBiblioteca: la consulta no dice que se busca y "
		+ "se repregunta, sin fuentes ni texto. Le sigue `fin` con `sinMaterial` en false. Las opciones son "
		+ "los aspectos del tema que la biblioteca tiene; pueden venir vacias (\"¿Para qué afección o tema?\")")
public record ConsultaEventoAclaracion(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "La repregunta, lista para mostrar")
		String texto,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OpcionDeAclaracionResponse> opciones) {

	public static ConsultaEventoAclaracion de(Interpretacion.Aclarar aclarar) {
		return new ConsultaEventoAclaracion(aclarar.texto(),
				aclarar.opciones().stream().map(OpcionDeAclaracionResponse::de).toList());
	}
}
