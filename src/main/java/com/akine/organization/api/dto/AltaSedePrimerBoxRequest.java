package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El primer box de una sede, creado en el mismo acto que la sede (RF-M03-002, A-8).
 *
 * <p>Es un espacio de tipo BOX, igual que uno dado de alta por {@code POST .../espacios}, y desde
 * ahi se administra como cualquier otro. Los limites de nombre y capacidad son los mismos.
 */
@Schema(description = "Primer box de la sede, creado junto con ella")
public record AltaSedePrimerBoxRequest(

		@Schema(
				description = "Nombre del box. Unico entre los espacios vigentes de la sede",
				example = "Box 1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del primer box es obligatorio")
		@Size(max = 160, message = "El nombre del box no puede superar los 160 caracteres")
		String name,

		@Schema(
				description = "Personas que el box admite a la vez. Si se omite, 1",
				example = "1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "La capacidad minima es 1")
		@Max(value = 1000, message = "La capacidad maxima es 1000")
		Integer capacidad) {
}
