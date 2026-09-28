package com.akine.resource.infrastructure;

import com.akine.resource.domain.MedicionDefinicion;
import com.akine.resource.domain.port.MedicionDefinicionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acceso al catalogo de definiciones de medicion.
 *
 * <p>Las dos consultas filtran por {@code owner_key}, la columna generada que vale el id del
 * tenant o el centinela {@code 0} para lo global. <b>Nunca por {@code organization_id}</b>: varios
 * {@code NULL} no colisionan en MySQL y filtrar por la columna cruda dejaria fuera el catalogo de
 * plataforma entero — es el error que 02.05 pago y que ADR-0021 documenta. El razonamiento
 * completo esta en {@link MedicionDefinicionRepositoryPort}.
 */
public interface MedicionDefinicionRepository
		extends JpaRepository<MedicionDefinicion, Long>, MedicionDefinicionRepositoryPort {

	@Override
	@Query(value = """
			SELECT * FROM medicion_definicion
			 WHERE id = :id
			   AND owner_key IN (:owners)
			""", nativeQuery = true)
	Optional<MedicionDefinicion> findVisible(
			@Param("id") Long id, @Param("owners") Collection<Long> owners);

	@Override
	@Query(value = """
			SELECT * FROM medicion_definicion
			 WHERE owner_key IN (:owners)
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (name LIKE :patron OR codigo LIKE :patron)
			 ORDER BY name ASC
			""", nativeQuery = true)
	List<MedicionDefinicion> buscar(
			@Param("owners") Collection<Long> owners,
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro);
}
