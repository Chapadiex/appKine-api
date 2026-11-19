package com.akine.assistant.domain.exception;

/** La accion vencio, se descarto o la esta confirmando otro pedido: 409. */
public class AccionNoVigenteException extends RuntimeException {

	public AccionNoVigenteException() {
		super("La propuesta ya no esta vigente: volve a pedirla.");
	}
}
