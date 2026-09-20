package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.PlanItem;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanItemRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistencia de las practicas planificadas.
 *
 * <p><b>No expone borrado ni actualizacion de cantidades.</b> Modificar un plan no toca los items
 * de la version anterior: escribe los de la version nueva. Y no hay nada que actualizar despues,
 * porque las cantidades realizadas y canceladas <b>no son columnas</b> (RN-M11-001): se derivan al
 * leer por {@code clinical.spi.RealizadoEnElCasoProbe}.
 */
public interface PlanItemRepository
		extends JpaRepository<PlanItem, Long>, PlanItemRepositoryPort {

	@Override
	@Query("""
			SELECT i FROM PlanItem i
			 WHERE i.organizationId = :organizationId
			   AND i.planTratamientoVersionId = :planTratamientoVersionId
			 ORDER BY i.id ASC
			""")
	List<PlanItem> buscarDeVersion(
			@Param("organizationId") Long organizationId,
			@Param("planTratamientoVersionId") Long planTratamientoVersionId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es un {@code DELETE} masivo por consulta y no un {@code deleteAll} de entidades: se ejecuta
	 * de inmediato contra la base, asi que las filas viejas se van <b>antes</b> de que el
	 * {@code saveAll} de las nuevas llegue al unique {@code uk_plan_item_oferta}. Con un borrado por
	 * entidad, Hibernate podria ordenar los INSERT antes que los DELETE dentro del mismo flush y la
	 * edicion de un borrador que repite una oferta chocaria contra su propia fila anterior.
	 *
	 * <p>Lleva {@code organizationId} en el {@code WHERE} aunque el id de la version ya sea unico:
	 * un borrado sin predicado de tenant es la clase de consulta que, mal llamada, borra datos de
	 * otro centro.
	 */
	@Override
	@Modifying
	@Query("""
			DELETE FROM PlanItem i
			 WHERE i.organizationId = :organizationId
			   AND i.planTratamientoVersionId = :planTratamientoVersionId
			""")
	void borrarDeVersionEnBorrador(
			@Param("organizationId") Long organizationId,
			@Param("planTratamientoVersionId") Long planTratamientoVersionId);
}
