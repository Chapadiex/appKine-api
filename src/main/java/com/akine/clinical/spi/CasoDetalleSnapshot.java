package com.akine.clinical.spi;

import java.time.Instant;

/**
 * Lo que otro modulo puede mostrar de un Caso Clinico: su motivo y su estado (M31).
 *
 * <p>Es contenido clinico. Quien lo pide tiene que haber pasado antes por
 * {@link AccesoClinicoDirectory}: este record no autoriza nada.
 *
 * @param estado {@code ACTIVO} o {@code CERRADO}
 */
public record CasoDetalleSnapshot(
		long id,
		long organizationId,
		long historiaClinicaId,
		int numeroCaso,
		String estado,
		String diagnosticoPresuntivo,
		String objetivoTerapeutico,
		Instant abiertoEn) {

	public boolean activo() {
		return "ACTIVO".equals(estado);
	}
}
