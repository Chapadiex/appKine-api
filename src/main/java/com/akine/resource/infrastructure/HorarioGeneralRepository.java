package com.akine.resource.infrastructure;

import com.akine.resource.domain.FranjaHorarioGeneral;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.HorarioGeneralRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Horario general de una sede ({@code consultorio_horario}, V83). */
public interface HorarioGeneralRepository
		extends JpaRepository<FranjaHorarioGeneral, Long>, HorarioGeneralRepositoryPort {

	@Query("""
			SELECT f FROM FranjaHorarioGeneral f
			 WHERE f.organizationId = :organizationId
			   AND f.consultorioId = :consultorioId
			   AND f.active = true
			 ORDER BY f.diaSemana ASC, f.horaDesde ASC
			""")
	@Override
	List<FranjaHorarioGeneral> findVigentes(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);
}
