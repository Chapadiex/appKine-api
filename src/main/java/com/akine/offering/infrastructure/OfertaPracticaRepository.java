package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaPractica;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaPracticaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Adaptador JPA de las practicas de una oferta (A-9). {@code save} y {@code flush} los satisface
 * {@link JpaRepository} con su firma literal; las dos consultas son derivadas y Spring Data las
 * valida contra el esquema al levantar el contexto.
 */
public interface OfertaPracticaRepository
		extends JpaRepository<OfertaPractica, Long>, OfertaPracticaRepositoryPort {

	@Override
	List<OfertaPractica> findAllByOrganizationIdAndOfertaIdOrderByIdAsc(
			Long organizationId, Long ofertaId);

	@Override
	List<OfertaPractica> findAllByOrganizationIdAndOfertaIdAndActiveOrderByIdAsc(
			Long organizationId, Long ofertaId, boolean active);
}
