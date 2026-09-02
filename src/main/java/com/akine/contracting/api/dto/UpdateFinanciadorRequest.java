package com.akine.contracting.api.dto;

import com.akine.contracting.domain.TipoFinanciador;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Edicion parcial de un financiador.
 *
 * <p>Los campos en {@code null} NO se tocan. Para borrar un opcional se manda cadena vacia.
 *
 * <p><b>El codigo no esta y no es un olvido:</b> es la clave con la que las coberturas y los
 * convenios ya firmados lo referencian, y cambiarlo reescribiria el significado de filas que no
 * participan de esta llamada.
 */
@Schema(description = "Campos editables de un financiador. Los ausentes no se tocan")
public record UpdateFinanciadorRequest(

		@Schema(description = "Nuevo nombre. Unico entre los vigentes de la organizacion",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(description = "Nueva clasificacion", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		TipoFinanciador tipo,

		@Schema(description = "Nuevo CUIT, normalizado a 11 digitos antes de guardar",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 20, message = "El CUIT no puede superar los 20 caracteres")
		String cuit,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Email(message = "El email de contacto no tiene un formato valido")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String emailContacto,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String telefonoContacto,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones,

		@Schema(
				description = "Version leida. Si no coincide con la vigente la edicion se rechaza "
						+ "con 409 conflict en vez de pisar el cambio de otro",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
