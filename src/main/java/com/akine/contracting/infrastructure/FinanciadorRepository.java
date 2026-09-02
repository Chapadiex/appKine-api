package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Acceso a los financiadores de una organizacion (V41).
 *
 * <p><b>Toda consulta lleva {@code organizationId}</b>, a diferencia de
 * {@code ServicioRepository}: no hay financiador global en 03.03. Ver
 * {@code ContractingRepositoryPorts.FinanciadorRepositoryPort}.
 *
 * <p>{@code save}, {@code saveAndFlush} y las derivadas las satisface {@link JpaRepository} tal
 * cual: las firmas del puerto coinciden con las suyas.
 */
public interface FinanciadorRepository
		extends JpaRepository<Financiador, Long>, FinanciadorRepositoryPort {

	/**
	 * Nativa a proposito, mismo motivo que {@code ServicioRepository.buscar}: el centinela
	 * {@code activoFiltro = -1} se compara contra la columna {@code TINYINT} de MySQL, y en JPQL
	 * un campo {@code boolean} no se compara directamente contra un entero. Escribirla en SQL
	 * ademas deja ver el plan que corre sobre {@code ix_financiador_estado_nombre}.
	 *
	 * <p>El {@code tipo} si puede ser nulo sin romper nada: es un {@code VARCHAR} y el
	 * {@code :tipo IS NULL} lo resuelve Hibernate sin ambiguedad de tipo.
	 */
	@Override
	@Query(value = """
			SELECT * FROM financiador
			 WHERE organization_id = :organizationId
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (:tipo IS NULL OR tipo = :tipo)
			   AND (nombre LIKE :patron OR codigo LIKE :patron OR cuit LIKE :patron)
			 ORDER BY nombre ASC
			""", nativeQuery = true)
	List<Financiador> buscar(
			@Param("organizationId") Long organizationId,
			@Param("patron") String patron,
			@Param("activoFiltro") int activoFiltro,
			@Param("tipo") String tipo);
}
