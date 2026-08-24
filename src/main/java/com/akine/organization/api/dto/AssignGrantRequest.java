package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Otorgamiento de un permiso adicional sobre un vinculo (matriz seccion 6, "No por defecto").
 *
 * <p>El catalogo de codigos otorgables es cerrado y lo decide el dominio: cualquier otro valor
 * —incluido un permiso que existe pero no es otorgable en esta fase— se rechaza con 400
 * {@code validation-error}, no con una fila que despues nadie pueda explicar.
 *
 * <p>{@code validUntil} es opcional. Sin el, el permiso adicional rige hasta que alguien lo de
 * de baja; con el, vence solo. Preferir la fecha cuando el motivo es temporal es lo que evita
 * que un permiso concedido "por esta semana" siga vivo un ano despues.
 */
@Schema(description = "Permiso adicional a otorgar sobre un vinculo")
public record AssignGrantRequest(

		@Schema(
				description = "Codigo del permiso, del catalogo de la matriz",
				example = "auditoria:read-clinica",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo de permiso es obligatorio")
		@Size(max = 60, message = "El codigo de permiso no puede superar los 60 caracteres")
		String permissionCode,

		@Schema(
				description = "Motivo declarado del otorgamiento. Queda en la auditoria",
				example = "Revision de historia clinica por reclamo del financiador",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason,

		@Schema(
				description = "Vencimiento del permiso adicional. Sin el, rige hasta que se de "
						+ "de baja",
				example = "2026-12-31T23:59:59Z",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validUntil) {
}
