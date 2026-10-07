package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TurnoOmitido", description = "Un turno del alcance que la operacion de serie deja como esta")
public record TurnoOmitidoResponse(

		TurnoResponse turno,

		@Schema(
				description = "`YA_EMPEZO` (el pasado es inalterable), `ESTADO_TERMINAL` (ya cancelado "
						+ "o ausente), `EN_ESPERA` (el paciente esta en la sala: se resuelve solo) o "
						+ "`CON_ATENCION` (tiene una Sesion registrada)",
				allowableValues = {"YA_EMPEZO", "ESTADO_TERMINAL", "EN_ESPERA", "CON_ATENCION"},
				example = "YA_EMPEZO")
		String motivo) {
}
