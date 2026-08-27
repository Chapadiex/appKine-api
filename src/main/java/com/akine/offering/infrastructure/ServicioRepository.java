package com.akine.offering.infrastructure;

import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Acceso al catalogo global de Servicios. <b>Sin {@code organizationId}, y no es un olvido:</b>
 * la tabla {@code servicio} (V24) es global por ADR-0023. Si alguien le agrega el parametro — a
 * esta interfaz, a la consulta nativa de abajo o al {@code spi} — es que entendio mal el
 * alcance: la configuracion propia de cada centro vive en {@code oferta_servicio_consultorio},
 * no aca. Ver el javadoc completo en {@code ServicioRepositoryPort}.
 *
 * <p>{@code save}, {@code saveAndFlush} y {@code findById} los satisface {@link JpaRepository}
 * tal cual: las firmas del puerto coinciden con las suyas, no hace falta redeclararlas.
 */
public interface ServicioRepository extends JpaRepository<Servicio, Long>, ServicioRepositoryPort {

	/**
	 * Nativa a proposito, mismo motivo que {@code EspecialidadRepository.buscar}: el sentinela
	 * {@code activoFiltro = -1} se compara contra la columna {@code TINYINT} de MySQL, y en JPQL
	 * un campo {@code boolean} no se compara directamente contra un entero. Escribirla en SQL
	 * ademas deja ver exactamente el plan que corre sobre {@code ix_servicio_estado_nombre}.
	 */
	@Override
	@Query(value = """
			SELECT * FROM servicio
			 WHERE (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (nombre LIKE :patron OR codigo LIKE :patron)
			 ORDER BY nombre ASC
			""", nativeQuery = true)
	List<Servicio> buscar(@Param("patron") String patron, @Param("activoFiltro") int activoFiltro);
}
