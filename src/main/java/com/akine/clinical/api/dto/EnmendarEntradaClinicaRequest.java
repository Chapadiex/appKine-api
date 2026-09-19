package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Enmienda de una entrada clinica: contenido nuevo que NO pisa el anterior (RF-M09-006).
 *
 * <p><b>El motivo es obligatorio, y se valida dos veces a proposito.</b> Aca con
 * {@code @NotBlank}, que da un 400 {@code validation-error} con el campo señalado, y otra vez en
 * el dominio, que da un 400 {@code enmienda-sin-motivo}. La de aca es comodidad para el
 * formulario; la del dominio es la que vale, porque ningun camino de escritura —ni uno futuro que
 * no pase por este endpoint— puede construir una enmienda sin motivo.
 *
 * <p>Sin motivo, una enmienda es indistinguible de una correccion de tipeo y el historial deja de
 * servir para lo unico que sirve: entender por que cambio el texto (RN-M09-004).
 */
@Schema(description = "Enmienda de una entrada clinica. Escribe una version nueva; la anterior "
		+ "queda intacta y consultable")
public record EnmendarEntradaClinicaRequest(

		@Schema(description = "Texto clinico corregido, completo. No es un parche",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El cuerpo de la enmienda es obligatorio")
		@Size(max = 20000, message = "El cuerpo no puede superar los 20000 caracteres")
		String cuerpo,

		@Schema(description = "Por que se enmienda. Obligatorio",
				example = "Se corrigio la lateralidad: era rodilla izquierda",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la enmienda es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo,

		@Schema(description = "Version de la CABECERA que el autor leyo, para el bloqueo "
				+ "optimista. Si alguien enmendo o dio de baja la entrada en el medio, esto "
				+ "responde 409 en vez de apilar una version sobre un texto que el autor nunca "
				+ "vio. NO es numeroVersion",
				example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
