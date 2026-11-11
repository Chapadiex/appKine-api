package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Evento SSE {@code etapa}: en que anda la consulta. */
@Schema(description = "Evento `etapa` del stream de consultarBiblioteca: `buscando` al salir de la cola y "
		+ "`redactando` antes del primer `texto` generado")
public record ConsultaEventoEtapa(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"buscando", "redactando"})
		String etapa) {

	public static final String BUSCANDO = "buscando";
	public static final String REDACTANDO = "redactando";
}
