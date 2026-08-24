package com.akine.organization.domain.exception;

/**
 * El actor intento quitarse a si mismo su ultimo rol administrativo en la organizacion.
 *
 * <p><b>Se rechaza aunque queden otros administradores</b>, y ese es el punto: despues de la
 * operacion el actor ya no puede deshacerla. Una accion irreversible por distraccion no
 * deberia ser un PATCH cualquiera.
 *
 * <p>Si un {@code ORG_ADMIN} quiere irse, el camino es que <b>otro</b> admin lo revoque. Si es
 * el unico, tiene que promover a alguien antes. Es exactamente la secuencia que el invariante
 * de ultimo admin quiere forzar, y aca se fuerza un paso antes.
 *
 * <p>409 y no 403, por el mismo motivo que {@link LastAdminException}: el permiso esta; lo que
 * no admite la operacion es el estado resultante.
 */
public class SelfRevokeNotAllowedException extends RuntimeException {

	private final long accountId;

	public SelfRevokeNotAllowedException(long accountId) {
		super("Un actor no puede quitarse a si mismo su ultimo rol administrativo");
		this.accountId = accountId;
	}

	public long getAccountId() {
		return accountId;
	}
}
