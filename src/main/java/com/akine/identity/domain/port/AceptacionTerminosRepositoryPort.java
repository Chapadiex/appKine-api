package com.akine.identity.domain.port;

import com.akine.identity.domain.AceptacionTerminos;

/** Persistencia de las aceptaciones de terminos (DP-45). Solo se agregan filas. */
public interface AceptacionTerminosRepositoryPort {

	AceptacionTerminos save(AceptacionTerminos aceptacion);
}
