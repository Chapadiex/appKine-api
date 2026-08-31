package com.akine.scheduling.domain.exception;

/**
 * El slot existe pero ya no tiene cupo. <b>409</b>, con un tipo propio.
 *
 * <p>La etapa pide explicitamente un conflicto especifico y no un error generico: "ya no hay
 * lugar" y "ese horario no existe" llevan a la pantalla a acciones distintas —la primera a ofrecer
 * el turno siguiente, la segunda a recargar la agenda—.
 *
 * <p>Lleva el cupo para que la pantalla pueda decir "se tomo el ultimo lugar" en vez de una frase
 * generica.
 */
public class SlotCompletoException extends RuntimeException {

	private final int cupoTotal;

	public SlotCompletoException(int cupoTotal) {
		super("El turno ya no tiene cupo: " + cupoTotal + " de " + cupoTotal + " tomados");
		this.cupoTotal = cupoTotal;
	}

	public int getCupoTotal() {
		return cupoTotal;
	}
}
