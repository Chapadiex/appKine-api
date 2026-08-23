package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta self-service: cuenta del fundador, organizacion y primer consultorio en un solo paso
 * (RF-M02-001, ADR-0008).
 *
 * <p><b>Aca si se valida el formato del email</b>, al reves que en el login. La diferencia no
 * es un descuido: este endpoint responde {@code 202} uniforme tanto si la direccion esta libre
 * como si ya tiene cuenta (ADR-0018), asi que el 400 por formato invalido no distingue nada
 * sobre la base —solo dice que lo tipeado no es una direccion—. En el login, en cambio, un 400
 * por formato seria la unica respuesta distinta del 401 y por ahi se filtraria informacion.
 *
 * <p>La contrasena se valida contra la politica en {@code application} y no con anotaciones:
 * la regla es de negocio (RNF-M02-008) y tiene que valer tambien para una invitacion o un
 * reset, que no pasan por este DTO.
 */
@Schema(description = "Alta self-service de cuenta, organizacion y primer consultorio")
public record RegisterAccountRequest(

		@Schema(
				description = "Direccion de correo del fundador. Sera su identidad unica",
				example = "ana.perez@ejemplo.test",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El email es obligatorio")
		@Email(message = "El email no tiene un formato valido")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String email,

		@Schema(
				description = "Contrasena en claro. La politica minima la valida el backend",
				example = "una-contrasena-larga-y-unica",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La contrasena es obligatoria")
		@Size(max = 256, message = "La contrasena no puede superar los 256 caracteres")
		String password,

		@Schema(description = "Nombre de la persona", example = "Ana",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre es obligatorio")
		@Size(max = 80, message = "El nombre no puede superar los 80 caracteres")
		String firstName,

		@Schema(description = "Apellido de la persona", example = "Perez",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El apellido es obligatorio")
		@Size(max = 80, message = "El apellido no puede superar los 80 caracteres")
		String lastName,

		@Schema(description = "Nombre del centro que se da de alta",
				example = "Centro Kinesico Belgrano",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre de la organizacion es obligatorio")
		@Size(max = 160, message = "El nombre de la organizacion no puede superar los 160 caracteres")
		String organizationName,

		@Schema(
				description = "Identificador legible del tenant. Si se omite se deriva del "
						+ "nombre y se resuelve cualquier colision",
				example = "centro-kinesico-belgrano",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "El slug no puede superar los 64 caracteres")
		String organizationSlug,

		@Schema(
				description = "Nombre de la primera sede. Si se omite se usa el de la organizacion",
				example = "Sede Central",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre del consultorio no puede superar los 160 caracteres")
		String consultorioName,

		@Schema(
				description = "Plan a contratar, del catalogo de GET /api/v1/plans. Si se omite "
						+ "se usa el plan por defecto",
				example = "BASICO",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "El codigo de plan no puede superar los 32 caracteres")
		String planCode) {
}
