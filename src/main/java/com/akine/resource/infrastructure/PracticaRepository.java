package com.akine.resource.infrastructure;

import com.akine.resource.domain.Practica;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a las prestaciones.
 *
 * <p>La consulta de busqueda es la de RF-M06-004 y corre sobre {@code ix_practica_busqueda} o
 * sobre {@code ix_practica_owner_especialidad} segun se filtre o no por especialidad.
 *
 * <p><b>El {@code LIKE} no lleva ningun {@code LOWER()} ni ningun {@code REPLACE()} y es
 * deliberado:</b> la collation de la tabla es {@code utf8mb4_0900_ai_ci}, insensible a
 * mayusculas y a acentos, asi que el motor ya resuelve la busqueda normalizada. Envolver la
 * columna en una funcion, ademas de ser redundante, dejaria el indice sin usar.
 */
public interface PracticaRepository
		extends JpaRepository<Practica, Long>, CatalogoRepositoryPorts.PracticaPort {

	@Override
	@Query(value = """
			SELECT * FROM practica
			 WHERE id = :id
			   AND owner_key IN (:owners)
			""", nativeQuery = true)
	Optional<Practica> findVisible(
			@Param("id") Long id, @Param("owners") Collection<Long> owners);

	@Override
	@Query(value = """
			SELECT * FROM practica
			 WHERE owner_key IN (:owners)
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (:especialidadId = -1 OR especialidad_id = :especialidadId)
			   AND (name LIKE :patron OR codigo LIKE :patron)
			 ORDER BY name ASC
			""", nativeQuery = true)
	List<Practica> buscar(
			@Param("owners") Collection<Long> owners,
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro,
			@Param("especialidadId") long especialidadId);

	@Override
	@Query(value = """
			SELECT COUNT(*) FROM practica
			 WHERE especialidad_id = :especialidadId
			   AND active = 1
			""", nativeQuery = true)
	long contarVigentesPorEspecialidad(@Param("especialidadId") Long especialidadId);
}
