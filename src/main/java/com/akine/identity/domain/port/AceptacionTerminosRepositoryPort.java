package com.akine.identity.domain.port;

import com.akine.identity.domain.AceptacionTerminos;

import java.util.Optional;

/** Persistencia de las aceptaciones de terminos (DP-45). Solo se agregan filas. */
public interface AceptacionTerminosRepositoryPort {

	AceptacionTerminos save(AceptacionTerminos aceptacion);

	boolean existsByCuentaIdAndVersion(Long cuentaId, String version);

	Optional<AceptacionTerminos> findByCuentaIdAndVersion(Long cuentaId, String version);
}
