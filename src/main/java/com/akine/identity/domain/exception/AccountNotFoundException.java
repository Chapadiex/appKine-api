package com.akine.identity.domain.exception;

/**
 * La cuenta no existe, o no es alcanzable para quien pregunta.
 *
 * <p>Las dos cosas son el mismo caso a proposito: una cuenta que existe pero pertenece a otra
 * organizacion se responde igual que una que no existe (404, nunca 403). Distinguirlas
 * dejaria que un administrador enumere las cuentas de organizaciones ajenas preguntando por
 * ids.
 */
public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(long accountId) {
		super("Cuenta inexistente o no alcanzable: " + accountId);
	}
}
