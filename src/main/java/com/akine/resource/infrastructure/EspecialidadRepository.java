package com.akine.resource.infrastructure;

import com.akine.resource.domain.Especialidad;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a las especialidades clinicas.
 *
 * <p>Las dos consultas filtran por {@code owner_key}, la columna generada que vale el id del
 * tenant o el centinela {@code 0} para lo global. El servicio arma la lista de duenios visibles
 * despues de validar el contexto; un id de otro tenant no aparece en ella y por lo tanto no
 * resuelve nunca. El razonamiento completo esta en {@link CatalogoRepositoryPorts}.
 */
public interface EspecialidadRepository
		extends JpaRepository<Especialidad, Long>, CatalogoRepositoryPorts.EspecialidadPort {

	@Override
	@Query(value = """
			SELECT * FROM especialidad
			 WHERE id = :id
			   AND owner_key IN (:owners)
			""", nativeQuery = true)
	Optional<Especialidad> findVisible(
			@Param("id") Long id, @Param("owners") Collection<Long> owners);

	@Override
	@Query(value = """
			SELECT * FROM especialidad
			 WHERE owner_key IN (:owners)
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (name LIKE :patron OR codigo LIKE :patron)
			 ORDER BY name ASC
			""", nativeQuery = true)
	List<Especialidad> buscar(
			@Param("owners") Collection<Long> owners,
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro);
}
