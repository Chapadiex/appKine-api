package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Otorgamiento del rol de plataforma a una cuenta existente.
 *
 * <p><b>Entra por id de cuenta y no por email, a proposito.</b> {@code cuenta} es propiedad de
 * {@code identity} y {@code organization} no compila contra ese modulo; resolver un email desde
 * aca exigiria una flecha que ArchUnit rechaza. Quien opera estas pantallas ya trabaja con ids
 * —los ve en la auditoria de plataforma— y este es el permiso mas alto del sistema: escribir el
 * id exacto es la friccion correcta.
 */
@Schema(description = "Rol de plataforma a otorgar")
public record GrantPlatformRoleRequest(

		@Schema(
				description = "Cuenta que recibe el rol",
				example = "18",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La cuenta destino es obligatoria")
		@Positive(message = "La cuenta destino debe ser un identificador valido")
		Long accountId,

		@Schema(
				description = "Motivo declarado. Otorgar el permiso mas alto del sistema lo exige",
				example = "Alta del segundo administrador de plataforma, ticket OPS-118",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason) {
}
