package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Reapertura de un caso cerrado, con motivo (RF-M10-006).
 *
 * <p>El estado vuelve a {@code ACTIVO} y no aparece ningun estado nuevo: RF-M10-006 pide reabrir,
 * no un tercer valor. Lo que hace revisable la reapertura despues es el historial del caso, no una
 * columna.
 *
 * <p><b>No reinicia la numeracion de sesiones del caso.</b> La sesion siguiente es la 9, no la 1:
 * renumerar seria reescribir historia clinica.
 */
@Schema(description = "Reapertura de un Caso Clinico cerrado")
public record ReabrirCasoClinicoRequest(

		@Schema(description = "Por que se reabre",
				example = "Recidiva del mismo cuadro a las tres semanas del alta",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La reapertura de un caso exige un motivo declarado")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String motivo,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista", example = "4",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
