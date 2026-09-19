package com.akine.clinical.api.dto;

import com.akine.clinical.domain.TipoEntradaClinica;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Alta de una entrada clinica con su version 1 (RF-M09-006).
 *
 * <p><b>No lleva {@code origen}.</b> Toda entrada que nace por esta ruta es {@code MANUAL}, y
 * dejar que el cliente lo elija le permitiria declarar que una entrada viene de una sesion que
 * nunca ocurrio. Cuando otro modulo registre entradas propias lo va a hacer por su servicio, no
 * por este endpoint.
 */
@Schema(description = "Alta de una entrada clinica. Se crea con su version 1 en el mismo acto")
public record RegistrarEntradaClinicaRequest(

		@Schema(description = "Clase de hecho clinico", example = "EVOLUCION",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de entrada es obligatorio")
		TipoEntradaClinica tipo,

		@Schema(description = "Texto clinico de la entrada",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El cuerpo de la entrada es obligatorio")
		@Size(max = 20000, message = "El cuerpo no puede superar los 20000 caracteres")
		String cuerpo,

		@Schema(description = "Instante UTC del hecho clinico. Si no viene, se usa ahora. PUEDE "
				+ "ser anterior a ahora —una evolucion se carga al final del dia— y nunca "
				+ "posterior: una entrada que declara haber ocurrido mañana desordena el timeline "
				+ "y se rechaza con 400")
		Instant ocurrioEn) {
}
