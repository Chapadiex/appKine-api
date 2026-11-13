package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Edicion de los datos mutables de un tenant.
 *
 * <p>El {@code slug} no esta y no es un olvido: se usa en URLs y en soporte, y renombrarlo
 * rompe enlaces. Cambiar de identificador es crear otro tenant, no editar este.
 *
 * <p>{@code version} es obligatorio. Es la version que devolvio el ultimo {@code GET}, y se
 * compara antes de mutar: si alguien mas edito la organizacion mientras el usuario tenia el
 * formulario abierto, la respuesta es 409 y hay que revalidar, en vez de pisar en silencio un
 * cambio ajeno. Se declara {@code Long} y no {@code long} para que omitirlo sea un 400
 * explicito y no un cero implicito que casualmente acierte con la version inicial.
 */
@Schema(description = "Campos editables de la organizacion, con la version para bloqueo optimista")
public record UpdateOrganizationRequest(

		@Schema(
				description = "Nuevo nombre del centro. Omitirlo o enviarlo vacio lo deja como esta",
				example = "Centro Kinesico Belgrano Norte",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(
				description = "Nueva zona horaria IANA de la organizacion: decide el dia de hoy en "
						+ "todo lo que se hace sin sede (DP-43). Omitirla o enviarla vacia la deja como "
						+ "esta; un offset fijo o una abreviatura es 400",
				example = "America/Argentina/Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "La zona horaria no puede superar los 64 caracteres")
		String timezone,

		@Schema(
				description = "Razon social del tenant (V140). La heredan las sedes sin datos fiscales "
						+ "propios. Omitirla la deja; cadena vacia la borra",
				example = "Centro Kinesico Belgrano SRL",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 200, message = "La razon social no puede superar los 200 caracteres")
		String legalName,

		@Schema(
				description = "Identificacion fiscal del tenant. Si tiene forma de CUIT (11 digitos, "
						+ "guiones opcionales) el digito verificador tiene que ser correcto: si no, 400 con "
						+ "errors.taxId. Cualquier otro formato se acepta como texto. Cadena vacia la borra",
				example = "30-71234567-1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "La identificacion fiscal no puede superar los 32 caracteres")
		String taxId,

		@Schema(
				description = "Domicilio fiscal del tenant, en una linea. Cadena vacia lo borra",
				example = "Av. Colon 1234, Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 240, message = "La direccion fiscal no puede superar los 240 caracteres")
		String fiscalAddressLine,

		@Schema(
				description = "Version que el cliente cree vigente, tal como la devolvio el GET",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version es obligatoria para el bloqueo optimista")
		@PositiveOrZero(message = "La version no puede ser negativa")
		Long version) {
}
