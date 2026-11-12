package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Reemplazo completo de la marca. Lo omitido o vacio se borra: un color borrado vuelve al de
 * AKINE. El contraste no se valida: la pantalla avisa y el centro decide.
 */
@Schema(description = "Marca completa de la organizacion, con la version para bloqueo optimista. "
		+ "Lo omitido o vacio se borra")
public record UpdateMarcaRequest(

		@Schema(description = "Color de accion en tema claro, #rrggbb", example = "#0f766e")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String colorPrincipalClaro,

		@Schema(description = "Color de enlaces y seleccion en tema claro, #rrggbb", example = "#115e59")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String colorAcentoClaro,

		@Schema(description = "Color de accion en tema oscuro, #rrggbb", example = "#2dd4bf")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String colorPrincipalOscuro,

		@Schema(description = "Color de enlaces y seleccion en tema oscuro, #rrggbb", example = "#5eead4")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String colorAcentoOscuro,

		@Schema(description = "Telefono de contacto", example = "+54 351 555-0100")
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String telefono,

		@Schema(description = "Email de contacto", example = "contacto@centro.example")
		@Email(message = "El email no tiene un formato valido")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String email,

		@Schema(description = "Sitio web, con http:// o https://", example = "https://centro.example")
		@Pattern(regexp = "^$|^https?://\\S+$", message = "El sitio web tiene que empezar con http:// o https://")
		@Size(max = 200, message = "El sitio web no puede superar los 200 caracteres")
		String sitioWeb,

		@Schema(description = "Direccion que se muestra en el pie", example = "Av. Colon 1234, Cordoba")
		@Size(max = 240, message = "La direccion no puede superar los 240 caracteres")
		String direccion,

		@Schema(description = "Linea libre del pie", example = "Lunes a viernes de 8 a 20 h")
		@Size(max = 200, message = "La leyenda no puede superar los 200 caracteres")
		String leyenda,

		@Schema(description = "Version que el cliente cree vigente, tal como la devolvio el GET",
				example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version es obligatoria para el bloqueo optimista")
		@PositiveOrZero(message = "La version no puede ser negativa")
		Long version) {

	private static final String COLOR = "^$|^#[0-9a-fA-F]{6}$";
	private static final String MENSAJE_COLOR = "El color tiene que tener la forma #rrggbb";
}
