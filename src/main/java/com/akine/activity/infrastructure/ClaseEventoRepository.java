package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseEvento;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Historial de clases. <b>Append-only</b>: este contrato no expone actualizar ni borrar, y esa
 * ausencia es la garantia. Ver {@link ClaseEvento}.
 */
public interface ClaseEventoRepository
		extends JpaRepository<ClaseEvento, Long>, ClaseEventoRepositoryPort {

	@Override
	default ClaseEvento registrar(ClaseEvento evento) {
		return save(evento);
	}

	@Override
	@Query("""
			SELECT e FROM ClaseEvento e
			 WHERE e.organizationId = :organizationId
			   AND e.claseId = :claseId
			 ORDER BY e.ocurridoEn ASC, e.id ASC
			""")
	List<ClaseEvento> historial(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId);
}
