package com.akine.offering.domain.exception;

/** Ya hay un honorario por clase activo de la oferta cuyo periodo se pisa con el pedido (08.09b). */
public class HonorarioDeClaseSolapadoException extends RuntimeException {

	private final long honorarioExistenteId;
	private final String periodoExistente;

	public HonorarioDeClaseSolapadoException(long honorarioExistenteId, String periodoExistente) {
		super("Honorario por clase solapado con " + honorarioExistenteId + " (" + periodoExistente + ")");
		this.honorarioExistenteId = honorarioExistenteId;
		this.periodoExistente = periodoExistente;
	}

	public long getHonorarioExistenteId() {
		return honorarioExistenteId;
	}

	public String getPeriodoExistente() {
		return periodoExistente;
	}
}
