package com.akine.activity.domain;

/**
 * Codigos de permiso que este modulo evalua, en texto.
 *
 * <p>{@code activity} no importa {@code organization.domain.PermissionCode}: seria una dependencia
 * hacia el dominio de otro modulo y ArchUnit la rechaza. El evaluador recibe el codigo como texto
 * por el {@code spi} y lo resuelve contra el catalogo. Mismo patron que
 * {@code scheduling.domain.PermissionCodes}.
 */
public final class PermissionCodes {

	/** Ver clases programadas y su historial. */
	public static final String CLASE_READ = "clase:read";

	/** Programar, reprogramar y cancelar clases. */
	public static final String CLASE_MANAGE = "clase:manage";

	private PermissionCodes() {
	}
}
