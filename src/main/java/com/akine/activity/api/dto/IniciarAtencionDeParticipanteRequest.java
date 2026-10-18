package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/**
 * Abrir la atencion clinica de un participante (AKINE-08.05). <b>Ni un dato clinico</b>: el
 * contenido se carga despues, en la Sesion.
 */
@Schema(
		name = "IniciarAtencionDeParticipante",
		description = "Abre la Sesion del participante desde su derivacion clinica vigente.")
public record IniciarAtencionDeParticipanteRequest(

		@Schema(description = "La derivacion de la que cuelga la atencion. Opcional si el "
				+ "participante tiene una sola vigente; obligatoria si tiene varias (si falta, "
				+ "409 `derivacion-ambigua`)", example = "88")
		@Positive Long derivacionId) {
}
