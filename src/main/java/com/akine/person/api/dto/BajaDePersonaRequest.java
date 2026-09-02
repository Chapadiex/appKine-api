package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Baja logica de una persona del padron (RF-M07-005).
 *
 * <p><b>El motivo es obligatorio.</b> Mismo criterio que la baja de una oferta, de un espacio y de
 * un consultorio: sin motivo la auditoria no responde por que seis meses despues. La validacion
 * esta ademas en el dominio, porque una que solo vive en la capa web no protege a los llamadores
 * que no son la capa web.
 *
 * <p><b>La version tambien.</b> Dar de baja es una escritura como cualquier otra y sin control
 * optimista se puede cerrar una ficha que otro operador acaba de corregir, sin que nadie se
 * entere.
 */
@Schema(description = "Baja logica de una persona. No borra nada: la ficha se sigue leyendo")
public record BajaDePersonaRequest(

		@Schema(
				description = "Motivo declarado de la baja. Obligatorio",
				example = "Ficha duplicada; se unifico con la 118",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo,

		@Schema(
				description = "Version que el cliente leyo. Si no coincide, la operacion se "
						+ "rechaza con 409 en vez de pisar el cambio ajeno",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@PositiveOrZero(message = "La version esperada no puede ser negativa")
		long expectedVersion) {
}
