package com.akine.scheduling.domain.exception;

/**
 * El caso o el plan indicados no sirven para este turno: son de otra persona, estan cerrados,
 * suspendidos, o la prestacion no esta en el plan (TURNO.md §28).
 */
public class ContextoClinicoInvalidoException extends RuntimeException {

	public ContextoClinicoInvalidoException(String motivo) {
		super(motivo);
	}
}
