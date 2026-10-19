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

	/** Enmendar una sesion cerrada (U-25, DP-43). Antes era {@code sesion:register}. */
	public static final String SESION_AMEND = "sesion:amend";

	/** Reemplazar al profesional de una sesion abierta (U-24, DP-43). Solo CONSULTORIO_ADMIN. */
	public static final String SESION_REASSIGN = "sesion:reassign";

	/**
	 * Fijar la ventana para enmendar de la organizacion (U-25). Se reusa el de gestionar sedes con
	 * alcance organizacion: lo tiene el ORG_ADMIN, que es quien configura el centro, y la matriz §32
	 * no tiene fila para la politica clinica de la organizacion.
	 */
	public static final String CONSULTORIO_MANAGE = "consultorio:manage";

	private PermissionCodes() {
	}
}
