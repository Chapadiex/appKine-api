package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la baja logica de una excepcion de disponibilidad (RF-M05-004).
 *
 * <p>Mismo cuerpo que {@link DeactivateBloqueRequest} y <b>a proposito NO es la misma clase</b>:
 * son dos operaciones distintas del contrato y compartir el schema ataria una a la otra: el dia
 * que la baja de una excepcion necesite un campo mas —por ejemplo, si reponer la disponibilidad
 * afectada es opcional— agregarselo al schema compartido se lo agregaria tambien a la baja de
 * bloques, que no lo pidio.
 */
@Schema(description = "Motivo de la baja logica de la excepcion de disponibilidad")
public record DeactivateExcepcionRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la auditoria y en la fila de la "
						+ "excepcion",
				example = "La licencia se cancelo: el profesional finalmente atiende esa semana",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
