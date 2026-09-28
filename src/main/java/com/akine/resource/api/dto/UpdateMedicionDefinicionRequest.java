package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Edicion parcial de una definicion de medida. Los campos omitidos no se modifican.
 *
 * <p><b>Ni el codigo, ni el alcance, ni el tipo estan aca</b>, y los tres por el mismo motivo: son
 * lo que las mediciones ya tomadas copiaron en su fila o lo que decide en que columna vive su
 * valor. Cambiarlos reescribiria hacia atras.
 *
 * <p><b>Ningun componente es un primitivo:</b> Jackson 3 pasa {@code null} por cada componente
 * ausente de un record y {@code FAIL_ON_NULL_FOR_PRIMITIVES} lo rechaza con un 400 que no nombra
 * el campo. En un DTO de PATCH la mitad de los campos siempre viene ausente.
 */
@Schema(description = "Cambios a aplicar sobre una definicion de medida")
public record UpdateMedicionDefinicionRequest(

		@Schema(description = "Nuevo nombre visible",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Nueva descripcion. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(
				description = "Nueva unidad. Cambiarla NO altera las mediciones ya tomadas: cada "
						+ "una guarda la unidad que regia cuando se tomo, y la comparacion se "
						+ "niega a restar valores de unidades distintas",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 24, message = "La unidad no puede superar los 24 caracteres")
		String unidad,

		@Schema(description = "Nuevo piso del rango. Se ignora si clearRango es true",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		BigDecimal minimo,

		@Schema(description = "Nuevo techo del rango. Se ignora si clearRango es true",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		BigDecimal maximo,

		@Schema(
				description = "Saca el rango de la definicion. Hace explicito lo que un campo "
						+ "omitido no puede expresar: 'no toques el rango' y 'sacale el rango' "
						+ "son dos intenciones distintas",
				example = "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean clearRango,

		@Schema(
				description = "Version que el cliente leyo. Si quedo vieja, 409 "
						+ "concurrent-modification y hay que recargar",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version es obligatoria para editar una definicion de medida")
		Long version) {
}
