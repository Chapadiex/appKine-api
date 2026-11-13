package com.akine.organization.application;

import com.akine.organization.domain.ColoresDeInterfaz;

/** Reemplazo completo de la marca: lo que llega {@code null} o vacio se borra. */
public record MarcaCommand(
		String colorPrincipalClaro,
		String colorAcentoClaro,
		String colorPrincipalOscuro,
		String colorAcentoOscuro,
		ColoresDeInterfaz interfazClaro,
		ColoresDeInterfaz interfazOscuro,
		String telefono,
		String email,
		String sitioWeb,
		String direccion,
		String leyenda) {
}
