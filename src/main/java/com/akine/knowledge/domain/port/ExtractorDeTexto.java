package com.akine.knowledge.domain.port;

import java.util.List;

import com.akine.knowledge.domain.PaginaDeTexto;

public interface ExtractorDeTexto {

	/**
	 * @throws com.akine.knowledge.domain.exception.DocumentoIlegibleException si el PDF esta cifrado,
	 *         danado o tiene mas de {@code maxPaginas} paginas
	 */
	List<PaginaDeTexto> extraer(byte[] pdf, int maxPaginas);
}
