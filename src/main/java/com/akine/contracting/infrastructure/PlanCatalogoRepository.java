package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.PlanCatalogo;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCatalogoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a los planes del catalogo global (V117, DP-43). Las firmas del puerto son derivadas. */
public interface PlanCatalogoRepository
		extends JpaRepository<PlanCatalogo, Long>, PlanCatalogoRepositoryPort {
}
