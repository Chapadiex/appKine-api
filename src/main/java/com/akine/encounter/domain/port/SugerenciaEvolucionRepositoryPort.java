package com.akine.encounter.domain.port;

import com.akine.encounter.domain.SugerenciaEvolucion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/** Las sugerencias de evolucion de un caso (M31 fase 2). Sin borrado. */
public interface SugerenciaEvolucionRepositoryPort {

	SugerenciaEvolucion save(SugerenciaEvolucion sugerencia);

	List<SugerenciaEvolucion> saveAll(List<SugerenciaEvolucion> sugerencias);

	Optional<SugerenciaEvolucion> findById(long organizationId, long id);

	/** El historial del caso, de la mas nueva a la mas vieja. */
	Page<SugerenciaEvolucion> findDelCaso(long organizationId, long casoId, Pageable pageable);
}
