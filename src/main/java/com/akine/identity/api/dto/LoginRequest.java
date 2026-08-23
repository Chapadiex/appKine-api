package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credenciales del login (RF-M02-002).
 *
 * <p><b>No hay validacion de formato de email aca, a proposito.</b> Un {@code @Email} que
 * rechazara "pepe" con 400 y aceptara "pepe@nada.com" con 401 le contaria a quien sondea que
 * la segunda direccion llego a evaluarse. La uniformidad de ADR-0018 empieza en la validacion:
 * todo lo que no este vacio entra al mismo camino y sale por el mismo 401.
 *
 * <p>El largo maximo si se acota: no es informacion sobre la cuenta, es proteccion contra un
 * cuerpo enorme que haga trabajar al hasheo de mas.
 */
@Schema(description = "Credenciales para abrir una sesion")
public record LoginRequest(

		@Schema(
				description = "Direccion de correo de la cuenta",
				example = "ana.perez@ejemplo.test",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El email es obligatorio")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String email,

		@Schema(
				description = "Contrasena en claro. Viaja solo por TLS y no se registra en "
						+ "ningun log",
				example = "una-contrasena-larga-y-unica",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La contrasena es obligatoria")
		@Size(max = 256, message = "La contrasena no puede superar los 256 caracteres")
		String password) {
}
