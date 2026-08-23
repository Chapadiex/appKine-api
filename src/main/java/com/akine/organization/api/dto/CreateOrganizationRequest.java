package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta administrativa de un tenant.
 *
 * <p>Este es el camino de operaciones y QA. El alta self-service NO pasa por aca: la resuelve
 * {@code InitialOrganizationProvisioning} desde el modulo {@code identity}, que ademas crea la
 * cuenta y la membership del fundador en la misma transaccion. Por eso este request no pide
 * datos de propietario: no hay ninguno.
 *
 * <p>El {@code slug} es opcional a proposito. Omitirlo lo deriva del nombre y resuelve
 * colisiones agregando un sufijo, que es lo que quiere quien da de alta un centro y no le
 * importa la URL. Enviarlo es para cuando el identificador ya esta acordado con el cliente.
 */
@Schema(description = "Datos para dar de alta una organizacion y su primer consultorio")
public record CreateOrganizationRequest(

		@Schema(
				description = "Nombre del centro",
				example = "Centro Kinesico Belgrano",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre de la organizacion es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(
				description = "Identificador legible. Si se omite, se deriva del nombre y se "
						+ "resuelve cualquier colision automaticamente",
				example = "centro-kinesico-belgrano",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "El slug no puede superar los 64 caracteres")
		String slug,

		@Schema(
				description = "Zona horaria IANA del centro. Si se omite, se usa la zona por "
						+ "defecto de la plataforma",
				example = "America/Argentina/Buenos_Aires",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "La zona horaria no puede superar los 64 caracteres")
		String timezone,

		@Schema(
				description = "Codigo del plan a contratar, del catalogo de GET /api/v1/plans",
				example = "BASICO",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo de plan es obligatorio")
		@Size(max = 32, message = "El codigo de plan no puede superar los 32 caracteres")
		String planCode) {
}
