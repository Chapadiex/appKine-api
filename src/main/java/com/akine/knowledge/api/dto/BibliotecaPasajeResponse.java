package com.akine.knowledge.api.dto;

import com.akine.knowledge.spi.PasajeRecuperado;
import io.swagger.v3.oas.annotations.media.Schema;

/** Un pasaje encontrado; el texto se recorta a 500 caracteres. */
@Schema(description = "Pasaje de un libro de la biblioteca")
public record BibliotecaPasajeResponse(
		long libroId, String titulo, int paginaDesde, int paginaHasta,
		@Schema(maxLength = 500) String texto,
		double puntaje, boolean superaCorte) {

	static final int TEXTO_MAXIMO = 500;

	public static BibliotecaPasajeResponse de(PasajeRecuperado p) {
		String texto = p.texto();
		if (texto.length() > TEXTO_MAXIMO) {
			int fin = Character.isHighSurrogate(texto.charAt(TEXTO_MAXIMO - 1)) ? TEXTO_MAXIMO - 1 : TEXTO_MAXIMO;
			texto = texto.substring(0, fin);
		}
		return new BibliotecaPasajeResponse(p.libroId(), p.titulo(), p.paginaDesde(), p.paginaHasta(),
				texto, p.puntaje(), p.superaCorte());
	}
}
