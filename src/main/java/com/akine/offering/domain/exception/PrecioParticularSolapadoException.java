package com.akine.offering.domain.exception;

/** Ya hay un precio particular activo de la oferta cuyo periodo se pisa con el pedido (B-3). */
public class PrecioParticularSolapadoException extends RuntimeException {

	private final long precioExistenteId;
	private final String periodoExistente;

	public PrecioParticularSolapadoException(long precioExistenteId, String periodoExistente) {
		super("Precio particular solapado con " + precioExistenteId + " (" + periodoExistente + ")");
		this.precioExistenteId = precioExistenteId;
		this.periodoExistente = periodoExistente;
	}

	public long getPrecioExistenteId() {
		return precioExistenteId;
	}

	public String getPeriodoExistente() {
		return periodoExistente;
	}
}
