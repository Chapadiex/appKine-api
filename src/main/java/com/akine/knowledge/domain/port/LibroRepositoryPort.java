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

	/** Libro activo de la organizacion con ese SHA-256 (incluye reemplazos pendientes). */
	Optional<Libro> activoPorHash(long orgId, String hashSha256);

	/** El libro que reemplaza (pendiente) al indicado, si hay uno. */
	Optional<Libro> reemplazoPendienteDe(long libroId);

	/** El libro PENDIENTE mas viejo, bloqueado con SKIP LOCKED. Exige transaccion abierta. */
	Optional<Libro> reclamarPendiente(Instant ahora);

	/** Devuelve a PENDIENTE los PROCESANDO con el lease anterior a {@code limite}. */
	int devolverTrabados(Instant limite);
}
