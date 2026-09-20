package com.akine.activity.domain.exception;

/**
 * La capacidad pedida no es sostenible (RN-M28-002, RF-M12-012).
 *
 * <p>Lleva el maximo para que la pantalla corrija el numero sola en vez de solo mostrar el error.
 * Es la misma idea que {@code maxDays} en la ventana de agenda de M05 y M12.
 */
public class CapacidadNoAdmitidaException extends RuntimeException {

	private final int capacidadPedida;
	private final int capacidadMaxima;
	private final String motivo;

	public CapacidadNoAdmitidaException(int capacidadPedida, int capacidadMaxima, String motivo) {
		super("La capacidad " + capacidadPedida + " no es admisible: " + motivo);
		this.capacidadPedida = capacidadPedida;
		this.capacidadMaxima = capacidadMaxima;
		this.motivo = motivo;
	}

	public int getCapacidadPedida() {
		return capacidadPedida;
	}

	public int getCapacidadMaxima() {
		return capacidadMaxima;
	}

	public String getMotivo() {
		return motivo;
	}
}
