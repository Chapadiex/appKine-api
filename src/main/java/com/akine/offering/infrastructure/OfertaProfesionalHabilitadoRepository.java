package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaProfesionalHabilitado;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaProfesionalHabilitadoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Adaptador JPA de las habilitaciones de profesional.
 *
 * <p>Toda consulta empieza por {@code organizationId}, y no es cosmetico: es lo que hace que el
 * indice sirva y que una habilitacion de otro tenant sea inalcanzable desde este repositorio
 * aunque el {@code ofertaId} se adivine.
 */
public interface OfertaProfesionalHabilitadoRepository
		extends JpaRepository<OfertaProfesionalHabilitado, Long>,
		OfertaProfesionalHabilitadoRepositoryPort {

	@Override
	List<OfertaProfesionalHabilitado> findAllByOrganizationIdAndOfertaId(
			Long organizationId, Long ofertaId);

	@Override
	List<OfertaProfesionalHabilitado> findAllByOrganizationIdAndOfertaIdAndActive(
			Long organizationId, Long ofertaId, boolean active);

	@Override
	long countByOrganizationIdAndMembershipIdAndActive(
			Long organizationId, Long membershipId, boolean active);
}
