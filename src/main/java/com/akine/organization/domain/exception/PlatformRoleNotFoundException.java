package com.akine.organization.domain.exception;

/**
 * El rol de plataforma pedido no existe o ya fue revocado.
 *
 * <p>404 comun: las rutas de plataforma solo las alcanza un {@code PLATFORM_ADMIN} y su
 * existencia no es secreta (el contrato OpenAPI las publica), asi que aca no hay nada que
 * proteger de la enumeracion.
 */
public class PlatformRoleNotFoundException extends RuntimeException {

	private final Long platformRoleId;

	public PlatformRoleNotFoundException(Long platformRoleId) {
		super("El rol de plataforma solicitado no existe o ya fue revocado");
		this.platformRoleId = platformRoleId;
	}

	public Long getPlatformRoleId() {
		return platformRoleId;
	}
}
