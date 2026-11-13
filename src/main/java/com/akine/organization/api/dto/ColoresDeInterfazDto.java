package com.akine.organization.api.dto;

import com.akine.organization.domain.ColoresDeInterfaz;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

@Schema(name = "ColoresDeInterfaz", description = "Colores de las zonas de la interfaz para un "
		+ "tema. Cada uno es opcional: ausente o vacio significa que se deriva del color principal")
public record ColoresDeInterfazDto(

		@Schema(description = "Fondo de la barra lateral de navegacion, #rrggbb", example = "#0f766e")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String barraLateral,

		@Schema(description = "Fondo de la barra superior, #rrggbb", example = "#ecfdf5")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String cabecera,

		@Schema(description = "Fondo de la pagina, detras de las tarjetas, #rrggbb", example = "#f0fdfa")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String fondo,

		@Schema(description = "Fondo de las tarjetas, paneles y campos, #rrggbb", example = "#ffffff")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String tarjeta,

		@Schema(description = "Fondo del pie de pagina, #rrggbb", example = "#f0fdfa")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String pie,

		@Schema(description = "Contorno y texto de los botones de contorno, #rrggbb", example = "#115e59")
		@Pattern(regexp = COLOR, message = MENSAJE_COLOR)
		String botonSecundario) {

	private static final String COLOR = "^$|^#[0-9a-fA-F]{6}$";
	private static final String MENSAJE_COLOR = "El color tiene que tener la forma #rrggbb";

	static ColoresDeInterfazDto from(ColoresDeInterfaz c) {
		return c == null || ColoresDeInterfaz.NINGUNO.equals(c)
				? null
				: new ColoresDeInterfazDto(
						c.barraLateral(), c.cabecera(), c.fondo(), c.tarjeta(), c.pie(), c.botonSecundario());
	}

	public ColoresDeInterfaz aDominio() {
		return new ColoresDeInterfaz(barraLateral, cabecera, fondo, tarjeta, pie, botonSecundario);
	}
}
