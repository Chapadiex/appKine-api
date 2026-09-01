package com.akine.billing.domain;

/**
 * Codigos de permiso que este modulo evalua, en texto.
 *
 * <p>{@code billing} no importa {@code organization.domain.PermissionCode}: seria una dependencia
 * hacia el dominio de otro modulo y ArchUnit la rechaza.
 */
public final class PermissionCodes {

	/**
	 * Ver y administrar deuda y cobros.
	 *
	 * <p>Existia en el catalogo desde 00.03 sin que lo tuviera nadie; AKINE-07.01 le da asignacion
	 * base. Cubre la lectura de la cuenta corriente y la anulacion; el registro del cobro en si es
	 * AKINE-07.02 y usa el mismo codigo, porque separar "ver deuda" de "cobrar" exigiria un permiso
	 * mas que la matriz §5 no tiene.
	 */
	public static final String COBRO_REGISTER = "cobro:register";

	private PermissionCodes() {
	}
}
