package com.akine.encounter.domain;

/**
 * Codigos de permiso que este modulo evalua, en texto.
 *
 * <p>{@code encounter} no importa {@code organization.domain.PermissionCode}: seria una dependencia
 * hacia el dominio de otro modulo y ArchUnit la rechaza. Mismo patron que {@code resource},
 * {@code person} y {@code scheduling}.
 */
public final class PermissionCodes {

	/** Registrar una atencion. Existia en el catalogo desde 00.03; 06.01 le da asignacion base. */
	public static final String SESION_REGISTER = "sesion:register";

	private PermissionCodes() {
	}
}
