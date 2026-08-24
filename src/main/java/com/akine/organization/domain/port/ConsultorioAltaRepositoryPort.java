package com.akine.organization.domain.port;

import com.akine.organization.domain.ConsultorioAlta;

import java.util.Optional;

/**
 * Registro de idempotencia del alta de sedes adicionales.
 *
 * <p>La clave lleva alcance tenant (ADR-0004): dos organizaciones distintas pueden usar el
 * mismo valor sin pisarse, y ninguna consulta se resuelve sin {@code organizationId}.
 */
public interface ConsultorioAltaRepositoryPort {

	Optional<ConsultorioAlta> findByOrganizationIdAndIdempotencyKey(
			Long organizationId, String idempotencyKey);

	/**
	 * Guarda y FUERZA el flush, para que la violacion de {@code uk_consultorio_alta_key}
	 * aparezca donde se la puede traducir y no al cerrar la transaccion.
	 */
	ConsultorioAlta saveAndFlush(ConsultorioAlta alta);
}
