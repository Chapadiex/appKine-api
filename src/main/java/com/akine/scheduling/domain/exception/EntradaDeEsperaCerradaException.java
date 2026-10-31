package com.akine.scheduling.domain.exception;

/** La entrada ya se asigno o se descarto: otra persona de recepcion pudo haberla resuelto antes. */
public class EntradaDeEsperaCerradaException extends RuntimeException {

	public EntradaDeEsperaCerradaException(Long entradaId, String estado) {
		super("La entrada " + entradaId + " de la lista de espera ya esta " + estado.toLowerCase());
	}
}
