package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Centro dado de alta y la invitacion enviada a su duenio")
public record AltaDeCentroResponse(
		@Schema(description = "Organizacion creada", example = "12") long organizationId,
		@Schema(description = "Primera sede", example = "20") long consultorioId,
		@Schema(description = "Invitacion al duenio", example = "31") long invitacionId,
		@Schema(description = "Correo al que se envio la invitacion", example = "ana@centro.com") String emailDuenio) {
}
