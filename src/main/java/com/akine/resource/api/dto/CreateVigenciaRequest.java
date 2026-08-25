package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Alta de una vigencia de un codigo dentro de un nomenclador (RF-M06-003, RN-M06-003).
 *
 * <p><b>No lleva alcance:</b> una vigencia hereda el duenio de su nomenclador y no puede
 * apartarse de el.
 *
 * <p><b>Actualizar el valor de un codigo no es editar la fila anterior.</b> Es cerrar la
 * vigencia vieja —ponerle {@code validUntil}— y crear una nueva desde ese mismo instante.
 * Editar la vieja borraria lo que ese codigo decia cuando se uso, que es lo que RN-M06-002
 * protege. Dos vigencias que se pisan responden 409 nomenclador-vigencia-overlap; dos
 * consecutivas NO se pisan, porque el fin es exclusivo.
 */
@Schema(description = "Nueva vigencia de un codigo dentro de un nomenclador")
public record CreateVigenciaRequest(

		@Schema(
				description = "Prestacion que este codigo representa. Tiene que ser visible y "
						+ "estar vigente. Una vigencia de un nomenclador GLOBAL solo puede "
						+ "codificar una practica GLOBAL",
				example = "1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La practica que codifica la vigencia es obligatoria")
		Long practicaId,

		@Schema(
				description = "Codigo dentro del nomenclador. Se repite entre vigencias del "
						+ "mismo concepto a proposito: cada fila es una version de ese codigo",
				example = "27.01.01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo de la vigencia es obligatorio")
		@Size(max = 48, message = "El codigo no puede superar los 48 caracteres")
		String codigo,

		@Schema(
				description = "Denominacion publicada para ESTA vigencia. Se guarda por vigencia "
						+ "porque los renombres son parte de la historia",
				example = "Sesion de kinesiologia motora",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La denominacion de la vigencia es obligatoria")
		@Size(max = 160, message = "La denominacion no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Descripcion libre",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(
				description = "Valor o unidades que publica el nomenclador para esta ventana. NO "
						+ "es el arancel cobrable: el arancel lo fija el convenio y puede "
						+ "apartarse de este",
				example = "1250.0000",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@PositiveOrZero(message = "El valor de referencia no puede ser negativo")
		BigDecimal valorReferencia,

		@Schema(description = "Inicio de la vigencia, en UTC. Si se omite, el momento del alta",
				example = "2026-01-01T00:00:00Z",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validFrom,

		@Schema(description = "Fin de la vigencia, EXCLUSIVO. Omitirlo la deja abierta",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validUntil) {
}
