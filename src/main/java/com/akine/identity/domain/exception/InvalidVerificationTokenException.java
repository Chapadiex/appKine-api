package com.akine.identity.domain.exception;

/**
 * El token de activacion o de reset no sirve.
 *
 * <p>Cubre inexistente, ya usado, invalidado por uno mas nuevo y vencido, sin distinguir
 * cual: decirle "expirado" a quien presenta un token le confirma que acerto un valor real, y
 * eso es exactamente la informacion que un atacante que prueba tokens necesita para saber que
 * va por buen camino.
 */
public class InvalidVerificationTokenException extends RuntimeException {

	public InvalidVerificationTokenException() {
		super("Token de verificacion invalido");
	}
}
