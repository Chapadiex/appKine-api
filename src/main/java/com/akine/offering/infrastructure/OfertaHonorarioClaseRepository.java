package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaHonorarioClase;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaHonorarioClaseRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de {@code oferta_honorario_clase} (AKINE-08.09b). Consultas derivadas: todas llevan
 * {@code organizationId}.
 */
public interface OfertaHonorarioClaseRepository
		extends JpaRepository<OfertaHonorarioClase, Long>, OfertaHonorarioClaseRepositoryPort {

	@Override
	List<OfertaHonorarioClase> findAllByOrganizationIdAndOfertaIdOrderByVigenciaDesdeDescIdDesc(
			Long organizationId, Long ofertaId);

	@Override
	List<OfertaHonorarioClase>
			findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
					Long organizationId, Long ofertaId, boolean active);

	@Override
	List<OfertaHonorarioClase> findAllByOrganizationIdAndOfertaIdInAndActive(
			Long organizationId, Collection<Long> ofertaIds, boolean active);

	@Override
	Optional<OfertaHonorarioClase> findByIdAndOrganizationIdAndOfertaId(
			Long id, Long organizationId, Long ofertaId);
}
