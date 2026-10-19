package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.FinanciadorCatalogo;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorCatalogoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Acceso al catalogo GLOBAL de financiadores (V117, DP-43). Sin {@code organizationId}: la tabla
 * no lo tiene (ADR-0023). Nativa por el mismo motivo que {@code FinanciadorRepository.buscar}.
 */
public interface FinanciadorCatalogoRepository
		extends JpaRepository<FinanciadorCatalogo, Long>, FinanciadorCatalogoRepositoryPort {

	@Override
	@Query(value = """
			SELECT * FROM financiador_catalogo
			 WHERE (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (:tipo IS NULL OR tipo = :tipo)
			   AND (nombre LIKE :patron OR codigo LIKE :patron OR cuit LIKE :patron)
			 ORDER BY nombre ASC
			""", nativeQuery = true)
	List<FinanciadorCatalogo> buscar(
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro,
			@Param("tipo") String tipo);
}
