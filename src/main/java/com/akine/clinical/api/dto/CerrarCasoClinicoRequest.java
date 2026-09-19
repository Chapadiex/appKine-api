package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cierre de un Caso Clinico, con motivo (RF-M10-006).
 *
 * <p><b>Sin motivo, un cierre es indistinguible de un abandono</b> y el historial deja de servir
 * para lo unico que sirve. Se valida aca y otra vez en el dominio: la de aca es comodidad para el
 * formulario, la del dominio es la que vale.
 *
 * <p>Cerrar <b>no</b> es dar de baja. El caso se sigue leyendo entero con todo su historial; lo
 * que no admite son sesiones nuevas ni edicion de contenido clinico.
 */
@Schema(description = "Cierre de un Caso Clinico. Cerrar no es borrar")
public record CerrarCasoClinicoRequest(

		@Schema(description = "Por que se cierra", example = "Alta por objetivos cumplidos",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El cierre de un caso exige un motivo declarado")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String motivo,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista", example = "3",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
