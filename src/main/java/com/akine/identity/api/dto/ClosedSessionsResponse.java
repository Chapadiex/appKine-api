package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuantas sesiones vivas se cortaron al cerrar todo.
 *
 * <p>Se publica el numero porque la cuenta es la propia: es informacion sobre uno mismo y es
 * justamente lo que hace util la pantalla —"se cerraron 3 sesiones" le confirma a la persona
 * que habia dispositivos que no recordaba—. Cero es un resultado valido, no un error.
 */
@Schema(description = "Resultado del cierre masivo de sesiones de la propia cuenta")
public record ClosedSessionsResponse(

		@Schema(description = "Sesiones vivas que se revocaron", example = "3")
		int closedSessions) {
}
