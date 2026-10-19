package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.SesionReemplazoProfesional;
import com.akine.encounter.domain.port.SesionReemplazoProfesionalRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SesionReemplazoProfesionalRepository extends
		JpaRepository<SesionReemplazoProfesional, Long>, SesionReemplazoProfesionalRepositoryPort {
}
