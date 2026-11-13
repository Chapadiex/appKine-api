package com.akine.organization.api.dto;

import com.akine.organization.application.MarcaView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Marca de la organizacion: colores institucionales por tema, datos de "
		+ "contacto del pie de pagina y logos cargados. Un color ausente significa que se usa el "
		+ "de AKINE; un logo ausente, que no hay")
public record MarcaResponse(

		@Schema(description = "Organizacion duena de la marca", example = "1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long organizationId,

		@Schema(description = "Version a reenviar al editar. 0 si el centro nunca edito su marca",
				example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		long version,

		@Schema(description = "Color de accion (botones, foco) en tema claro", example = "#0f766e")
		String colorPrincipalClaro,

		@Schema(description = "Color de enlaces y seleccion en tema claro", example = "#115e59")
		String colorAcentoClaro,

		@Schema(description = "Color de accion en tema oscuro", example = "#2dd4bf")
		String colorPrincipalOscuro,

		@Schema(description = "Color de enlaces y seleccion en tema oscuro", example = "#5eead4")
		String colorAcentoOscuro,

		@Schema(description = "Colores de barra lateral, cabecera, fondo y boton secundario en "
				+ "tema claro, si el centro fijo alguno")
		ColoresDeInterfazDto interfazClaro,

		@Schema(description = "Lo mismo para tema oscuro")
		ColoresDeInterfazDto interfazOscuro,

		@Schema(description = "Telefono de contacto del centro", example = "+54 351 555-0100")
		String telefono,

		@Schema(description = "Email de contacto del centro", example = "contacto@centro.example")
		String email,

		@Schema(description = "Sitio web del centro", example = "https://centro.example")
		String sitioWeb,

		@Schema(description = "Direccion que se muestra en el pie", example = "Av. Colon 1234, Cordoba")
		String direccion,

		@Schema(description = "Linea libre del pie: horario, matricula, lema",
				example = "Lunes a viernes de 8 a 20 h")
		String leyenda,

		@Schema(description = "Logo para tema claro, si hay")
		LogoDeMarcaResponse logoClaro,

		@Schema(description = "Logo para tema oscuro, si hay. Sin el, en oscuro se usa el claro")
		LogoDeMarcaResponse logoOscuro) {

	public static MarcaResponse from(MarcaView v) {
		return new MarcaResponse(
				v.organizationId(), v.version(),
				v.colorPrincipalClaro(), v.colorAcentoClaro(),
				v.colorPrincipalOscuro(), v.colorAcentoOscuro(),
				ColoresDeInterfazDto.from(v.interfazClaro()), ColoresDeInterfazDto.from(v.interfazOscuro()),
				v.telefono(), v.email(), v.sitioWeb(), v.direccion(), v.leyenda(),
				LogoDeMarcaResponse.from(v.logoClaro()), LogoDeMarcaResponse.from(v.logoOscuro()));
	}
}
