package com.akine.resource.infrastructure;

import com.akine.resource.domain.Nomenclador;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los nomencladores.
 *
 * <p>Esta es la <b>raiz de bloqueo</b> del catalogo clinico:
 * {@link #findVisibleForUpdate(Long, Collection)} es la primera sentencia de toda alta de
 * vigencia. La consulta es NATIVA a proposito —igual que {@code findByIdForUpdate} en
 * espacios—: la clausula que decide la correccion, {@code FOR UPDATE}, tiene que estar a la
 * vista de quien lee la consulta y no escondida en una anotacion sobre un metodo derivado.
 */
public interface NomencladorRepository
		extends JpaRepository<Nomenclador, Long>, CatalogoRepositoryPorts.NomencladorPort {

	@Override
	@Query(value = """
			SELECT * FROM nomenclador
			 WHERE id = :id
			   AND owner_key IN (:owners)
			""", nativeQuery = true)
	Optional<Nomenclador> findVisible(
			@Param("id") Long id, @Param("owners") Collection<Long> owners);

	@Override
	@Query(value = """
			SELECT * FROM nomenclador
			 WHERE id = :id
			   AND owner_key IN (:owners)
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<Nomenclador> findVisibleForUpdate(
			@Param("id") Long id, @Param("owners") Collection<Long> owners);

	@Override
	@Query(value = """
			SELECT * FROM nomenclador
			 WHERE owner_key IN (:owners)
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (name LIKE :patron OR codigo LIKE :patron)
			 ORDER BY name ASC
			""", nativeQuery = true)
	List<Nomenclador> buscar(
			@Param("owners") Collection<Long> owners,
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro);
}
