package com.akine.organization.domain.exception;

/**
 * Ya existe un grant vigente de ese permiso sobre esa membership.
 *
 * <p><b>Nace de una clave duplicada, no de un chequeo previo.</b> Dos administradores otorgando
 * el mismo permiso a la vez leerian los dos "no existe" y los dos insertarian: el unique
 * {@code uk_membership_grant_activo} es quien decide, y el perdedor llega aca.
 *
 * <p>El camino que la produce respeta la leccion 3 de los bugs de concurrencia de 01.01/01.02:
 * {@code saveAndFlush} &rarr; {@code DataIntegrityViolationException} &rarr; esta excepcion, y
 * <b>ninguna operacion JPA despues del flush fallido</b>. Una sesion de JPA reusada tras un
 * flush que fallo tira {@code AssertionFailure} y convierte un 409 legitimo en un 500.
 */
public class GrantAlreadyActiveException extends RuntimeException {

	private final String permissionCode;
	private final Long membershipId;

	public GrantAlreadyActiveException(String permissionCode, Long membershipId) {
		super("Ese permiso adicional ya esta vigente sobre esta membership");
		this.permissionCode = permissionCode;
		this.membershipId = membershipId;
	}

	public String getPermissionCode() {
		return permissionCode;
	}

	public Long getMembershipId() {
		return membershipId;
	}
}
