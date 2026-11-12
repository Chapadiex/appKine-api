package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.port.LibroRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.akine.platform.spi.listado.TextoDeBusqueda;

import java.time.Instant;
import java.util.Optional;

public interface LibroRepository extends JpaRepository<Libro, Long>, LibroRepositoryPort {

	@Override
	@Query("select l from Libro l where l.id = :id and l.organizationId = :orgId")
	Optional<Libro> findByIdAndOrganization(@Param("id") long id, @Param("orgId") long orgId);

	/**
	 * {@code q} busca "contiene" en titulo y autores, sin mayusculas ni tildes: lo resuelve la
	 * collation {@code akine_ci} con un {@code LIKE} de patron escapado. No {@code locate()}: en
	 * PostgreSQL las busquedas de subcadena no admiten una collation no determinista.
	 */
	@Override
	default Page<Libro> buscar(long orgId, String q, String area, EstadoLibro estado, Boolean activo,
			Pageable pageable) {
		return buscarPorPatron(orgId, TextoDeBusqueda.patronLike(q), area, estado, activo, pageable);
	}

	@Query(value = """
			select l from Libro l
			 where l.organizationId = :orgId
			   and (:patron is null or l.titulo like :patron or coalesce(l.autores, '') like :patron)
			   and (:area is null or l.area = :area)
			   and (:estado is null or l.estado = :estado)
			   and (:activo is null or l.activo = :activo)
			""",
			countQuery = """
			select count(l) from Libro l
			 where l.organizationId = :orgId
			   and (:patron is null or l.titulo like :patron or coalesce(l.autores, '') like :patron)
			   and (:area is null or l.area = :area)
			   and (:estado is null or l.estado = :estado)
			   and (:activo is null or l.activo = :activo)
			""")
	Page<Libro> buscarPorPatron(@Param("orgId") long orgId, @Param("patron") String patron, @Param("area") String area,
			@Param("estado") EstadoLibro estado, @Param("activo") Boolean activo, Pageable pageable);

	@Override
	default Libro guardarYSincronizar(Libro libro) {
		return saveAndFlush(libro);
	}

	@Override
	@Query("""
			select l from Libro l
			 where l.organizationId = :orgId and l.hashSha256 = :hash and l.id <> :excluir
			   and ((l.activo = true and l.reemplazaLibroId is null)
			        or (l.reemplazaLibroId is not null
			            and l.estado <> com.akine.knowledge.domain.EstadoLibro.ERROR))
			""")
	Optional<Libro> hashEnUso(@Param("orgId") long orgId, @Param("hash") String hash,
			@Param("excluir") long excluirLibroId);

	@Override
	@Query("select l from Libro l where l.reemplazaLibroId = :libroId")
	Optional<Libro> reemplazoDe(@Param("libroId") long libroId);

	@Override
	@Query("""
			select l from Libro l
			 where l.reemplazaLibroId = :libroId
			   and l.estado <> com.akine.knowledge.domain.EstadoLibro.ERROR
			""")
	Optional<Libro> reemplazoPendienteDe(@Param("libroId") long libroId);

	@Query(value = """
			SELECT * FROM libro
			 WHERE estado = 'PENDIENTE'
			 ORDER BY id
			 LIMIT 1
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<Libro> reclamarPendienteNativo();

	@Override
	default Optional<Libro> reclamarPendiente(Instant ahora) {
		return reclamarPendienteNativo();
	}

	@Override
	@Query(value = """
			SELECT l.* FROM libro l
			 WHERE l.estado = 'LISTO' AND l.activo = true AND l.reemplaza_libro_id IS NULL
			   AND l.version_procesamiento < :vigente
			   AND NOT EXISTS (
			       SELECT 1 FROM libro r
			        WHERE r.reemplaza_libro_id = l.id
			          AND NOT (r.estado = 'ERROR' AND r.hash_sha256 = l.hash_sha256
			                   AND r.version_procesamiento > l.version_procesamiento AND r.intentos < :maxIntentos))
			 ORDER BY COALESCE((SELECT f.intentos FROM libro f WHERE f.reemplaza_libro_id = l.id), 0), l.id
			 LIMIT 1
			""", nativeQuery = true)
	Optional<Libro> aReprocesarPorVersion(@Param("vigente") int versionVigente,
			@Param("maxIntentos") int maxIntentos);

	@Override
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE libro
			   SET estado = 'PENDIENTE', procesando_desde = NULL, latido = NULL, progreso = 0,
			       version = version + 1
			 WHERE estado = 'PROCESANDO' AND (latido IS NULL OR latido < :limite)
			""", nativeQuery = true)
	int devolverTrabados(@Param("limite") Instant limite);

	@Override
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Libro l where l.id = :id")
	Optional<Libro> bloquearPorId(@Param("id") long id);

	@Override
	@Modifying
	@Query("""
			update Libro l set l.progreso = :progreso, l.latido = :latido
			 where l.id = :id
			   and l.estado = com.akine.knowledge.domain.EstadoLibro.PROCESANDO
			   and l.procesandoDesde = :desde
			""")
	int actualizarProgreso(@Param("id") long id, @Param("desde") Instant procesandoDesde,
			@Param("progreso") int progreso, @Param("latido") Instant latido);

	@Override
	@Modifying
	@Query("""
			update Libro l
			   set l.estado = com.akine.knowledge.domain.EstadoLibro.ERROR,
			       l.motivoError = :motivo, l.procesandoDesde = null, l.latido = null,
			       l.version = l.version + 1, l.updatedAt = :ahora
			 where l.id = :id
			   and l.estado = com.akine.knowledge.domain.EstadoLibro.PROCESANDO
			   and l.procesandoDesde = :desde
			""")
	int marcarError(@Param("id") long id, @Param("desde") Instant procesandoDesde,
			@Param("motivo") String motivo, @Param("ahora") Instant ahora);
}
