package com.akine.identity.domain.exception;

/**
 * La contrasena propuesta no cumple la politica (RNF-M02-008: la regla vive en el backend).
 *
 * <p>Lleva un motivo legible porque aca SI conviene ser explicito: quien esta eligiendo una
 * contrasena es el duenio de la cuenta, y un "invalida" a secas lo deja adivinando. No hay
 * nada que enumerar: el mensaje habla de lo que la persona acaba de tipear, no de si existe
 * o no algo en la base.
 */
public class PasswordPolicyViolationException extends RuntimeException {

	public PasswordPolicyViolationException(String motivo) {
		super(motivo);
	}
}
