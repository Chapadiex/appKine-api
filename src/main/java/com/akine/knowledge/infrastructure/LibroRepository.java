package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.port.LibroRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface LibroRepository extends JpaRepository<Libro, Long>, LibroRepositoryPort {

	@Override
	@Query("select l from Libro l where l.id = :id and l.organizationId = :orgId")
	Optional<Libro> findByIdAndOrganization(@Param("id") long id, @Param("orgId") long orgId);

	@Override
	@Query(value = """
			select l from Libro l
			 where l.organizationId = :orgId
			   and (:q is null or locate(:q, l.titulo) > 0 or locate(:q, coalesce(l.autores, '')) > 0)
			   and (:area is null or l.area = :area)
			   and (:estado is null or l.estado = :estado)
			   and (:activo is null or l.activo = :activo)
			""",
			countQuery = """
			select count(l) from Libro l
			 where l.organizationId = :orgId
			   and (:q is null or locate(:q, l.titulo) > 0 or locate(:q, coalesce(l.autores, '')) > 0)
			   and (:area is null or l.area = :area)
			   and (:estado is null or l.estado = :estado)
			   and (:activo is null or l.activo = :activo)
			""")
	Page<Libro> buscar(@Param("orgId") long orgId, @Param("q") String q, @Param("area") String area,
			@Param("estado") EstadoLibro estado, @Param("activo") Boolean activo, Pageable pageable);

	/** Query nativa por SKIP LOCKED, como NotificationOutboxRepository. */
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
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE libro
			   SET estado = 'PENDIENTE', procesando_desde = NULL, progreso = 0, version = version + 1
			 WHERE estado = 'PROCESANDO' AND procesando_desde < :limite
			""", nativeQuery = true)
	int devolverTrabados(@Param("limite") Instant limite);
}
