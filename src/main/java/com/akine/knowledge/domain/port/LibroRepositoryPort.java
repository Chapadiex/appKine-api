package com.akine.knowledge.domain.port;

import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.Libro;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;

public interface LibroRepositoryPort {

	Libro save(Libro libro);

	Optional<Libro> findByIdAndOrganization(long id, long orgId);

	/**
	 * Listado de la organizacion. Los filtros en {@code null} no filtran. {@code q} busca en
	 * titulo y autores (sin distinguir mayusculas ni acentos, por la collation).
	 */
	Page<Libro> buscar(long orgId, String q, String area, EstadoLibro estado, Boolean activo,
			Pageable pageable);

	void delete(Libro libro);

	/** Guarda y fuerza el INSERT/UPDATE ya, para que un unique violado salte aca. */
	Libro guardarYSincronizar(Libro libro);

	/**
	 * Libro de la organizacion con ese SHA-256 que esta activo o es un reemplazo en curso (no
	 * ERROR), salvo {@code excluirLibroId}. Solo es confiable bajo el lock de la organizacion.
	 */
	Optional<Libro> hashEnUso(long orgId, String hashSha256, long excluirLibroId);

	/** Fuerza el SQL pendiente (un DELETE antes de un INSERT que depende de el). */
	void flush();

	/** El reemplazo del libro en cualquier estado, si hay uno. */
	Optional<Libro> reemplazoDe(long libroId);

	/** El reemplazo en curso (no ERROR) del libro, si hay uno. */
	Optional<Libro> reemplazoPendienteDe(long libroId);

	/** El libro PENDIENTE mas viejo, bloqueado con SKIP LOCKED. Exige transaccion abierta. */
	Optional<Libro> reclamarPendiente(Instant ahora);

	/** Devuelve a PENDIENTE los PROCESANDO con el lease anterior a {@code limite}. */
	int devolverTrabados(Instant limite);

	/** Relee el libro con FOR UPDATE. Exige transaccion abierta. */
	Optional<Libro> bloquearPorId(long id);

	/**
	 * Mueve solo {@code progreso} (sin avanzar la version) si el libro sigue PROCESANDO con ese
	 * lease. Devuelve las filas tocadas.
	 */
	int actualizarProgreso(long id, Instant procesandoDesde, int progreso);

	/**
	 * Pasa a ERROR con su motivo, avanzando la version, si el libro sigue PROCESANDO con ese
	 * lease. Devuelve las filas tocadas.
	 */
	int marcarError(long id, Instant procesandoDesde, String motivo, Instant ahora);
}
