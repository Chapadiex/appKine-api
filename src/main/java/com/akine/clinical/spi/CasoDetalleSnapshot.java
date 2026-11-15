package com.akine.clinical.spi;

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
		long historiaClinicaId,
		int numeroCaso,
		String estado,
		String diagnosticoPresuntivo,
		String objetivoTerapeutico) {

	public boolean activo() {
		return "ACTIVO".equals(estado);
	}
}
