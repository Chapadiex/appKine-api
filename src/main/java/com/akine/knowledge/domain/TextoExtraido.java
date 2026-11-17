package com.akine.knowledge.domain;

import java.util.List;

/** Reglas sobre el texto que se extrajo de un libro. */
public final class TextoExtraido {

	private static final int MIN_CARACTERES_POR_PAGINA = 50;

	private TextoExtraido() {
	}

	/** Menos de 50 caracteres no blancos por pagina en promedio (el escaneado). */
	public static boolean casiSinTexto(List<PaginaDeTexto> paginas) {
		if (paginas.isEmpty()) {
			return true;
		}
		long total = 0;
		for (PaginaDeTexto p : paginas) {
			String t = p.texto();
			if (t == null) {
				continue;
			}
			for (int i = 0; i < t.length(); i++) {
				if (!Character.isWhitespace(t.charAt(i))) {
					total++;
				}
			}
		}
		return total < (long) MIN_CARACTERES_POR_PAGINA * paginas.size();
	}
}
