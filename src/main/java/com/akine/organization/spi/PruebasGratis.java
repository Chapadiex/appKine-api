package com.akine.organization.spi;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Lo que necesita quien avisa del vencimiento de la prueba gratis (18/11/2026): que centros la
 * terminan en una ventana y a que cuentas avisarles. Lo consume {@code identity}, que resuelve los
 * correos y encola los avisos.
 */
public interface PruebasGratis {

	/** Centros vigentes en PRUEBA cuyo fin cae en {@code [desde, hasta)}. */
	List<PruebaEnCurso> queTerminanEntre(Instant desde, Instant hasta);

	/** Cuentas con ORG_ADMIN vigente en la organizacion. */
	Set<Long> administradores(long organizationId);

	record PruebaEnCurso(long organizationId, String nombreCentro, Instant pruebaHasta) {
	}
}
