package com.akine.scheduling.domain;

/**
 * Codigos de permiso que este modulo evalua, en texto.
 *
 * <p>{@code scheduling} no importa {@code organization.domain.PermissionCode}: seria una
 * dependencia hacia el dominio de otro modulo y ArchUnit la rechaza. El evaluador recibe el codigo
 * como texto por el {@code spi} y lo resuelve contra el catalogo. Mismo patron que
 * {@code resource.domain.PermissionCodes} y {@code person.domain.PermissionCodes}.
 */
public final class PermissionCodes {

	/** Ver la agenda y buscar turnos disponibles. */
	public static final String TURNO_READ = "turno:read";

	/** Reservar, confirmar y —desde 05.03— cancelar turnos. */
	public static final String TURNO_MANAGE = "turno:manage";

	private PermissionCodes() {
	}
}
