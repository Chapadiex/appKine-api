package com.akine.activity.infrastructure;

import com.akine.activity.domain.AsistenciaEvento;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Historial de asistencias. <b>Append-only</b>: este contrato no expone actualizar ni borrar, y esa
 * ausencia es la garantia. Ver {@link AsistenciaEvento}.
 */
public interface AsistenciaEventoRepository
		extends JpaRepository<AsistenciaEvento, Long>, AsistenciaEventoRepositoryPort {

	@Override
	default AsistenciaEvento registrar(AsistenciaEvento evento) {
		return save(evento);
	}

	@Override
	default List<AsistenciaEvento> registrarTodos(List<AsistenciaEvento> eventos) {
		return saveAll(eventos);
	}

	@Override
	@Query("""
			SELECT e FROM AsistenciaEvento e
			 WHERE e.organizationId = :organizationId
			   AND e.asistenciaId = :asistenciaId
			 ORDER BY e.ocurridoEn ASC, e.id ASC
			""")
	List<AsistenciaEvento> historial(
			@Param("organizationId") long organizationId,
			@Param("asistenciaId") long asistenciaId);
}
