package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaEspacioHabilitado;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaEspacioHabilitadoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Adaptador JPA de las habilitaciones de espacio. Mismo criterio que el de profesionales. */
public interface OfertaEspacioHabilitadoRepository
		extends JpaRepository<OfertaEspacioHabilitado, Long>,
		OfertaEspacioHabilitadoRepositoryPort {

	@Override
	List<OfertaEspacioHabilitado> findAllByOrganizationIdAndOfertaId(
			Long organizationId, Long ofertaId);

	@Override
	List<OfertaEspacioHabilitado> findAllByOrganizationIdAndOfertaIdAndActive(
			Long organizationId, Long ofertaId, boolean active);
}
