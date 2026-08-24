package com.akine.organization.infrastructure;

import com.akine.organization.domain.ConsultorioAlta;
import com.akine.organization.domain.port.ConsultorioAltaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Acceso al registro de idempotencia del alta de sedes. Siempre acotado al tenant. */
public interface ConsultorioAltaRepository
		extends JpaRepository<ConsultorioAlta, Long>, ConsultorioAltaRepositoryPort {

	Optional<ConsultorioAlta> findByOrganizationIdAndIdempotencyKey(
			Long organizationId, String idempotencyKey);
}
