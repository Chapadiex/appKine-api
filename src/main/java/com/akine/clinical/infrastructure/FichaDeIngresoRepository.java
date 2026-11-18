package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.FichaDeIngreso;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.FichaDeIngresoRepositoryPort;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Persistencia de {@link FichaDeIngreso}: una fila por historia ({@code uk_hc_ficha_historia}). */
public interface FichaDeIngresoRepository
		extends JpaRepository<FichaDeIngreso, Long>, FichaDeIngresoRepositoryPort {

	@Override
	@Query("""
			SELECT f FROM FichaDeIngreso f
			 WHERE f.organizationId = :organizationId AND f.historiaClinicaId = :historiaClinicaId
			""")
	Optional<FichaDeIngreso> buscarPorHistoria(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId);
}
