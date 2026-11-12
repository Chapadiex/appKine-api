package com.akine.knowledge.application;

import com.akine.knowledge.domain.EstadoVisibleLibro;
import com.akine.knowledge.domain.Libro;

/** Lo que la capa REST puede ver de un {@link Libro} (las entidades no salen por la API). */
public record LibroView(
		long id, String titulo, String autores, String edicion, String area, Integer paginas,
		EstadoVisibleLibro estado, int progreso, String motivoError, Long reemplazaLibroId,
		long version) {

	static LibroView de(Libro l) {
		return new LibroView(l.getId(), l.getTitulo(), l.getAutores(), l.getEdicion(), l.getArea(),
				l.getPaginas(), l.estadoVisible(), l.getProgreso(), l.getMotivoError(),
				l.getReemplazaLibroId(), l.getVersion());
	}
}
