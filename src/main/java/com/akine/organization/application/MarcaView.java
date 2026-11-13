package com.akine.organization.application;

import com.akine.organization.domain.ColoresDeInterfaz;

/**
 * Marca del tenant tal como la ve la capa {@code api}. Un color {@code null} es "el de AKINE";
 * un logo {@code null} es "no hay".
 *
 * @param version version de la fila de marca; 0 si el centro nunca la edito
 */
public record MarcaView(
		long organizationId,
		long version,
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
		String leyenda,
		Logo logoClaro,
		Logo logoOscuro) {

	/** Lo que se publica de un logo sin su contenido: el tipo y la huella que lo identifica. */
	public record Logo(String contentType, String sha256) {
	}
}
