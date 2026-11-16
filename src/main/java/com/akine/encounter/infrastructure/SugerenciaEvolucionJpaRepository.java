package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.SugerenciaEvolucion;
import com.akine.encounter.domain.port.SugerenciaEvolucionRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SugerenciaEvolucionJpaRepository
		extends JpaRepository<SugerenciaEvolucion, Long>, SugerenciaEvolucionRepositoryPort {

	/**
	 * Puente entre el {@code saveAll(List)} del puerto y el {@code saveAll(Iterable)} de Spring
	 * Data. Sin este metodo Spring Data lo toma por consulta derivada y la aplicacion no arranca.
	 */
	@Override
	default List<SugerenciaEvolucion> saveAll(List<SugerenciaEvolucion> sugerencias) {
		return saveAll((Iterable<SugerenciaEvolucion>) sugerencias);
	}

	@Override
	@Query("""
			SELECT s FROM SugerenciaEvolucion s
			 WHERE s.organizationId = :organizationId AND s.id = :id AND s.active = true
			""")
	Optional<SugerenciaEvolucion> findById(@Param("organizationId") long organizationId,
			@Param("id") long id);

	@Override
	@Query(value = """
			SELECT s FROM SugerenciaEvolucion s
			 WHERE s.organizationId = :organizationId AND s.casoId = :casoId AND s.active = true
			 ORDER BY s.solicitadaEn DESC, s.id DESC
			""", countQuery = """
			SELECT COUNT(s) FROM SugerenciaEvolucion s
			 WHERE s.organizationId = :organizationId AND s.casoId = :casoId AND s.active = true
			""")
	Page<SugerenciaEvolucion> findDelCaso(@Param("organizationId") long organizationId,
			@Param("casoId") long casoId, Pageable pageable);
}
