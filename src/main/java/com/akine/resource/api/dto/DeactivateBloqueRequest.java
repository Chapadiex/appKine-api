package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la baja logica de un bloque de disponibilidad (RF-M05-005, RN-M05-003).
 *
 * <p><b>Va en el cuerpo de un {@code DELETE}, y es deliberado.</b> La baja es LOGICA: la fila
 * sobrevive con su motivo y su autor, y sin motivo esa fila no se puede revisar seis meses
 * despues, que es exactamente cuando se la revisa. Un motivo en la query string quedaria en los
 * logs de acceso de cualquier proxy intermedio; en el cuerpo, no.
 */
@Schema(description = "Motivo de la baja logica del bloque de disponibilidad")
public record DeactivateBloqueRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la auditoria y en la fila del "
						+ "bloque",
				example = "El profesional dejo de atender los martes por la manana",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
