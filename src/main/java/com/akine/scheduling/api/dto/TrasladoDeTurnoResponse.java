package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TrasladoView;
import io.swagger.v3.oas.annotations.media.Schema;

/** El resultado de un cambio de sede. */
@Schema(
		name = "TrasladoDeTurno",
		description = "El turno original, ya cancelado, y el nuevo de la sede de destino, con `turnoOrigenId` "
				+ "apuntando al original.")
public record TrasladoDeTurnoResponse(

		@Schema(description = "El turno original, en estado CANCELADO")
		TurnoResponse original,

		@Schema(description = "El turno nuevo en la sede de destino, en estado RESERVADO")
		TurnoResponse nuevo) {

	public static TrasladoDeTurnoResponse de(TrasladoView vista) {
		return new TrasladoDeTurnoResponse(TurnoResponse.de(vista.original()), TurnoResponse.de(vista.nuevo()));
	}
}
