package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaPrecioParticular;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaPrecioParticularRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio de {@code oferta_precio_particular} (B-3, RF-M16-009). Consultas derivadas: todas
 * llevan {@code organizationId}.
 */
public interface OfertaPrecioParticularRepository
		extends JpaRepository<OfertaPrecioParticular, Long>, OfertaPrecioParticularRepositoryPort {

	@Override
	List<OfertaPrecioParticular> findAllByOrganizationIdAndOfertaIdOrderByVigenciaDesdeDescIdDesc(
			Long organizationId, Long ofertaId);

	@Override
	List<OfertaPrecioParticular>
			findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
					Long organizationId, Long ofertaId, boolean active);

	@Override
	Optional<OfertaPrecioParticular> findByIdAndOrganizationIdAndOfertaId(
			Long id, Long organizationId, Long ofertaId);
}
