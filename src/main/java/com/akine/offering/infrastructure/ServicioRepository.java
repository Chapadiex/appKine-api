package com.akine.offering.infrastructure;

import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los Servicios: catalogo global y servicios propios de cada centro (V143). Las lecturas
 * acotadas filtran por {@code owner_key}; ver el javadoc completo en {@code ServicioRepositoryPort}.
 *
 * <p>{@code save}, {@code saveAndFlush} y {@code findById} los satisface {@link JpaRepository}
 * tal cual: las firmas del puerto coinciden con las suyas, no hace falta redeclararlas.
 */
public interface ServicioRepository extends JpaRepository<Servicio, Long>, ServicioRepositoryPort {

	/**
	 * Nativa a proposito, mismo motivo que {@code EspecialidadRepository.buscar}: el sentinela
	 * {@code activoFiltro = -1} se compara contra la columna {@code TINYINT} de MySQL, y en JPQL
	 * un campo {@code boolean} no se compara directamente contra un entero. Usa
	 * {@code ix_servicio_owner_estado_nombre} (V143).
	 */
	@Override
	@Query(value = """
			SELECT * FROM servicio
			 WHERE owner_key IN (:owners)
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (nombre LIKE :patron OR codigo LIKE :patron)
			 ORDER BY nombre ASC
			""", nativeQuery = true)
	List<Servicio> buscarVisibles(
			@Param("owners") List<Long> owners,
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro);

	@Override
	@Query(value = "SELECT * FROM servicio WHERE id = :id AND owner_key IN (:owners)", nativeQuery = true)
	Optional<Servicio> findVisible(@Param("id") Long id, @Param("owners") List<Long> owners);
}
