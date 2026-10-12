package com.akine.billing.application;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * El perdedor de la carrera de RN-M21-003, dentro de la transaccion que va a revertir.
 *
 * <p>No sale del servicio: {@code PresentacionService} la atrapa cuando la transaccion ya termino
 * y la traduce a {@code ObligacionYaPresentadaException} con el lote que gano. Es una excepcion y
 * no un valor de retorno porque la transaccion tiene que revertir: el insert fallido dejo la sesion
 * JPA inutilizable.
 */
final class ObligacionOcupadaEnCarrera extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long organizationId;
	private final long obligacionId;
	private final transient DataIntegrityViolationException choque;

	ObligacionOcupadaEnCarrera(
			long organizationId, long obligacionId, DataIntegrityViolationException choque) {
		super("La obligacion " + obligacionId + " entro a otro lote en la misma ventana", choque);
		this.organizationId = organizationId;
		this.obligacionId = obligacionId;
		this.choque = choque;
	}

	long organizationId() {
		return organizationId;
	}

	long obligacionId() {
		return obligacionId;
	}

	DataIntegrityViolationException choque() {
		return choque;
	}
}
