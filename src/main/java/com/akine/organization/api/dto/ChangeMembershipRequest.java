package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cambio de rol y/o de alcance de un vinculo (RF-M02-004).
 *
 * <h2>Por que hay un {@code changeScope} y no alcanza con mandar {@code consultorioId}</h2>
 *
 * <p>{@code null} es un valor <b>legitimo</b> de {@code consultorioId}: significa "alcance de
 * toda la organizacion". Sin una bandera explicita, un PATCH que no quiere tocar la sede seria
 * indistinguible de uno que quiere ampliarla a la organizacion entera, y el servidor tendria que
 * adivinar. La bandera lo vuelve explicito y el contrato generado lo publica como tal, en vez de
 * depender de que el cliente omita un campo.
 *
 * <p>Los dos cambios se auditan por separado: mover a alguien de sede y cambiar lo que puede
 * hacer son dos decisiones distintas.
 */
@Schema(description = "Cambio de rol y/o de alcance de un vinculo")
public record ChangeMembershipRequest(

		@Schema(
				description = "Rol destino, o null para no tocarlo. PLATFORM_ADMIN no es un rol "
						+ "de vinculo y se rechaza con 400",
				example = "CONSULTORIO_ADMIN",
				allowableValues = {"ORG_ADMIN", "CONSULTORIO_ADMIN", "PROFESIONAL", "ADMINISTRATIVO"},
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		String roleCode,

		@Schema(
				description = "Indica que consultorioId debe aplicarse. En false la sede no se "
						+ "toca, sea cual sea el valor de consultorioId",
				example = "false",
				defaultValue = "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		boolean changeScope,

		@Schema(
				description = "Sede destino cuando changeScope es true. null significa alcance de "
						+ "toda la organizacion, no 'sin cambio'",
				example = "3",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long consultorioId,

		@Schema(
				description = "Motivo declarado del cambio. Queda en la auditoria",
				example = "Promocion a responsable de la sede Centro",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason) {
}
