package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.TurnoDeConsulta;
import io.swagger.v3.oas.annotations.media.Schema;

/** Un turno previo de la conversacion. Un {@code rol} fuera de los dos valores es 400. */
@Schema(description = "Turno previo de la conversacion")
public record TurnoDeConsultaRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"usuario", "asistente"}) String rol,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000) String texto) {

	/** Rol desconocido o ausente: turno sin rol, que el servicio rechaza con 400. */
	TurnoDeConsulta aTurno() {
		TurnoDeConsulta.Rol r = rol == null ? null : switch (rol) {
			case "usuario" -> TurnoDeConsulta.Rol.USUARIO;
			case "asistente" -> TurnoDeConsulta.Rol.ASISTENTE;
			default -> null;
		};
		return new TurnoDeConsulta(r, texto);
	}
}
