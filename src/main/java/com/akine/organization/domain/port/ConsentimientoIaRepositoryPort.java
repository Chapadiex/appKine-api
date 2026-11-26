package com.akine.organization.domain.port;

import com.akine.organization.domain.ConsentimientoIa;

import java.time.Instant;
import java.util.Optional;

/** Acceso a {@code organization_ia_consentimiento}: solo lectura e insercion. */
public interface ConsentimientoIaRepositoryPort {

	Optional<ConsentimientoIa> buscar(long organizationId, String version);

	/**
	 * Guarda la aceptacion si el centro todavia no acepto esa version
	 * ({@code ON CONFLICT DO NOTHING}).
	 *
	 * @return {@code true} si inserto la fila; {@code false} si ya existia
	 */
	boolean insertarSiNoExiste(long organizationId, String version, long accountId, String nombre, Instant aceptadoAt);
}
