package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a los planes de cobertura (V41).
 *
 * <p>Las cinco firmas del puerto son derivadas de Spring Data y ninguna necesita consulta escrita
 * a mano: el filtrado por estado del listado lo hace la capa de aplicacion sobre la lista
 * completa, porque un financiador tiene decenas de planes y no miles, y traerlos todos evita una
 * segunda consulta para la pantalla de administracion que los quiere todos igual.
 */
public interface PlanCoberturaRepository
		extends JpaRepository<PlanCobertura, Long>, PlanCoberturaRepositoryPort {
}
