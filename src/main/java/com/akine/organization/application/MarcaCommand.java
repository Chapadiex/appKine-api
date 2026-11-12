package com.akine.organization.application;

/** Reemplazo completo de la marca: lo que llega {@code null} o vacio se borra. */
public record MarcaCommand(
		String colorPrincipalClaro,
		String colorAcentoClaro,
		String colorPrincipalOscuro,
		String colorAcentoOscuro,
		String telefono,
		String email,
		String sitioWeb,
		String direccion,
		String leyenda) {
}
