package com.akine.identity.domain.exception;

/**
 * Las credenciales presentadas no habilitan una sesion.
 *
 * <p><b>Una sola excepcion para cuatro causas distintas</b>, y eso es la funcionalidad: el
 * email no existe, la contrasena es incorrecta, la cuenta esta bloqueada o esta desactivada.
 * Todas responden {@code 401 invalid-credentials} con el mismo cuerpo.
 *
 * <p>El caso incomodo es el cuarto: contrasena CORRECTA sobre una cuenta bloqueada tambien
 * responde esto, y si, castiga al usuario legitimo, que se entera por el canal
 * administrativo. La alternativa —un {@code 403 account-disabled} cuando la contrasena era
 * buena— convierte el login en un <b>oraculo de credenciales validas</b>: quien tenga una
 * lista filtrada de otro sitio descubre cuales sirven aca, aunque no pueda entrar. Sobre un
 * sistema que guarda historia clinica eso no se regala por comodidad.
 *
 * <p>La causa real se registra en auditoria, que es donde se puede investigar sin
 * devolversela a quien pregunta.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Credenciales invalidas");
	}
}
