package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.OcurrenciaOmitida;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.OcurrenciaOmitidaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Ocurrencias omitidas del alta de una serie (U-22). El puerto no expone ni modificacion ni borrado. */
public interface OcurrenciaOmitidaRepository
		extends JpaRepository<OcurrenciaOmitida, Long>, OcurrenciaOmitidaRepositoryPort {

	@Override
	default OcurrenciaOmitida registrar(OcurrenciaOmitida omitida) {
		return save(omitida);
	}

	@Override
	@Query("""
			SELECT o FROM OcurrenciaOmitida o
			 WHERE o.organizationId = :organizationId
			   AND o.serieId = :serieId
			 ORDER BY o.ocurrenciaInicio ASC
			""")
	List<OcurrenciaOmitida> deLaSerie(
			@Param("organizationId") long organizationId,
			@Param("serieId") long serieId);
}
