package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Evento SSE {@code espera}: la consulta esta en la cola; se emite cada vez que cambia la posicion. */
@Schema(description = "Evento `espera` del stream de consultarBiblioteca: la consulta espera en la cola de la "
		+ "instancia; se emite cada vez que cambia la posicion")
public record ConsultaEventoEspera(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1",
				description = "1 = la proxima en empezar") int posicion) {
}
