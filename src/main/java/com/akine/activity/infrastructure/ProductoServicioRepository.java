package com.akine.activity.infrastructure;

import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ProductoServicioRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Productos de pack de una sede (AKINE-08.06). Toda consulta lleva tenant y sede. */
public interface ProductoServicioRepository
		extends JpaRepository<ProductoServicio, Long>, ProductoServicioRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM ProductoServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND p.id = :productoId
			""")
	Optional<ProductoServicio> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("productoId") long productoId);

	@Override
	@Query("""
			SELECT p FROM ProductoServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND (:incluirBajas = true OR p.active = true)
			 ORDER BY p.active DESC, p.nombre ASC, p.id ASC
			""")
	List<ProductoServicio> findDeLaSede(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("incluirBajas") boolean incluirBajas);

	@Override
	@Query("""
			SELECT COUNT(p) > 0 FROM ProductoServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND p.nombre = :nombre
			   AND p.active = true
			   AND p.id <> :excluirProductoId
			""")
	boolean existeVigenteConNombre(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("nombre") String nombre,
			@Param("excluirProductoId") long excluirProductoId);

	@Override
	@Query(value = """
			SELECT * FROM producto_servicio
			 WHERE id = :productoId
			   AND organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<ProductoServicio> lockByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("productoId") long productoId);
}
