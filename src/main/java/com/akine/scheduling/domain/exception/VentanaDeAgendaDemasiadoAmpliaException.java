package com.akine.scheduling.domain.exception;

import java.time.LocalDate;

/** La ventana pedida supera el tope del buscador de turnos. <b>400</b>. */
public class VentanaDeAgendaDemasiadoAmpliaException extends RuntimeException {

	private final int maximoDias;

	public VentanaDeAgendaDemasiadoAmpliaException(LocalDate desde, LocalDate hasta, int maximoDias) {
		super("La ventana " + desde + ".." + hasta + " supera el maximo de " + maximoDias + " dias");
		this.maximoDias = maximoDias;
	}

	public int getMaximoDias() {
		return maximoDias;
	}
}
