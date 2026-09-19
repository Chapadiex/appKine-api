package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Edicion del contenido clinico de un caso (RF-M10-004).
 *
 * <p>Es un reemplazo completo de los dos campos y no un parche por campo ausente: un PATCH que
 * distingue "no lo mandes" de "vacialo" obliga al cliente a codificar esa diferencia, y la primera
 * pantalla que se olvide borra el objetivo del caso sin querer.
 *
 * <p>La oferta <b>no se edita</b>. Cambiarla convertiria el caso en otro caso, con el mismo numero
 * y el mismo historial: si lo que se trata es otra cosa, lo que corresponde es cerrar este y abrir
 * uno nuevo.
 */
@Schema(description = "Edicion del diagnostico y el objetivo de un Caso Clinico")
public record EditarCasoClinicoRequest(

		@Schema(description = "Diagnostico presuntivo, completo",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El caso exige un diagnostico presuntivo")
		@Size(max = 500, message = "El diagnostico no puede superar los 500 caracteres")
		String diagnosticoPresuntivo,

		@Schema(description = "Objetivo terapeutico, completo. Vacio lo borra")
		@Size(max = 1000, message = "El objetivo no puede superar los 1000 caracteres")
		String objetivoTerapeutico,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista. Dos "
				+ "profesionales del equipo editando el objetivo del mismo caso son el caso "
				+ "normal, no el raro: sin esto el segundo pisa al primero en silencio",
				example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
