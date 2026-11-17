package com.akine.encounter.domain.exception;

/**
 * La sugerencia de evolucion ya tiene respuesta: no se responde dos veces. <b>409
 * {@code sugerencia-ya-respondida}</b>. La primera respuesta no se pisa.
 */
public class SugerenciaYaRespondidaException extends RuntimeException {

	private final Long sugerenciaId;

	public SugerenciaYaRespondidaException(Long sugerenciaId) {
		super("La sugerencia " + sugerenciaId + " ya fue respondida");
		this.sugerenciaId = sugerenciaId;
	}

	public Long getSugerenciaId() {
		return sugerenciaId;
	}
}
