package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Edicion de una sede (RF-M03-003).
 *
 * <p>Semantica de PATCH: <b>cada campo omitido deja lo que estaba</b>. Para borrar un dato
 * institucional se manda cadena vacia. Un PUT obligaria al cliente a reenviar la sede entera y
 * convertiria cualquier campo que el frontend todavia no conozca en un borrado accidental.
 *
 * <p>{@code version} es obligatoria: es lo que impide que dos ediciones simultaneas se pisen. Si
 * quedo vieja, la respuesta es 409 {@code concurrent-modification} y el cliente recarga.
 *
 * <p><b>Cambiar la zona horaria no reinterpreta el pasado.</b> Los instantes ya registrados son
 * UTC y no se mueven; cambia como se proyectan de aca en adelante. La pantalla tiene que decirlo
 * con esas palabras.
 */
@Schema(description = "Cambios a aplicar sobre una sede. Los campos omitidos no se tocan")
public record UpdateConsultorioRequest(

		@Schema(description = "Nuevo nombre. Unico entre las sedes vigentes del tenant",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Nueva zona horaria IANA de la sede",
				example = "America/Argentina/Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "La zona horaria no puede superar los 64 caracteres")
		String timezone,

		@Schema(description = "Nuevo intervalo por defecto de la agenda, en minutos",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 5, message = "El intervalo minimo es de 5 minutos")
		@Max(value = 480, message = "El intervalo maximo es de 480 minutos")
		Integer slotMinutes,

		@Schema(description = "Razon social. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 200, message = "La razon social no puede superar los 200 caracteres")
		String legalName,

		@Schema(description = "Identificacion fiscal. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "La identificacion fiscal no puede superar los 32 caracteres")
		String taxId,

		@Schema(description = "Direccion. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 240, message = "La direccion no puede superar los 240 caracteres")
		String addressLine,

		@Schema(description = "Telefono. Cadena vacia lo borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String phone,

		@Schema(description = "Email de contacto. Cadena vacia lo borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Email(message = "El email de contacto no tiene un formato valido")
		@Size(max = 160, message = "El email no puede superar los 160 caracteres")
		String contactEmail,

		@Schema(
				description = "Version leida del recurso. Si quedo vieja, la edicion se rechaza "
						+ "con 409 en vez de pisar el cambio de otro",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@PositiveOrZero(message = "La version no puede ser negativa")
		long version) {
}
