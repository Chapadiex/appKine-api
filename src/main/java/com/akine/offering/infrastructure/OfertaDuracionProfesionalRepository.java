package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaDuracionProfesional;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaDuracionProfesionalRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Adaptador JPA de las duraciones propias. La cubre {@code ix_odp_oferta}. */
public interface OfertaDuracionProfesionalRepository
		extends JpaRepository<OfertaDuracionProfesional, Long>,
		OfertaDuracionProfesionalRepositoryPort {

	@Override
	List<OfertaDuracionProfesional> findAllByOrganizationIdAndOfertaIdAndActive(
			Long organizationId, Long ofertaId, boolean active);
}
