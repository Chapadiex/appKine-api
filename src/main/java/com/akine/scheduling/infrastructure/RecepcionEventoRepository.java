package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.RecepcionEvento;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Historial append-only de la recepcion. El puerto no expone ni modificacion ni borrado. */
public interface RecepcionEventoRepository
		extends JpaRepository<RecepcionEvento, Long>, RecepcionEventoRepositoryPort {

	@Override
	default RecepcionEvento registrar(RecepcionEvento evento) {
		return save(evento);
	}

	@Override
	@Query("""
			SELECT e FROM RecepcionEvento e
			 WHERE e.organizationId = :organizationId
			   AND e.turnoId = :turnoId
			 ORDER BY e.ocurridoEn ASC, e.id ASC
			""")
	List<RecepcionEvento> historial(
			@Param("organizationId") long organizationId,
			@Param("turnoId") long turnoId);
}
