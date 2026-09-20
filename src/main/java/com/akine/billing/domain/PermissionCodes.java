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

	/**
	 * Abrir, operar y cerrar la caja de una sede.
	 *
	 * <p>Existia en el catalogo desde 00.03 sin que lo tuviera nadie; AKINE-07.03 le da asignacion
	 * base segun la matriz §2, fila "Operar Caja": {@code ORG_ADMIN}, {@code CONSULTORIO_ADMIN} y
	 * {@code ADMINISTRATIVO} lo tienen; {@code PROFESIONAL} y {@code PLATFORM_ADMIN} no.
	 *
	 * <p><b>El cobro NO lo exige, y eso es una decision.</b> El movimiento de caja que un cobro
	 * genera es una consecuencia del cobro, no una operacion de caja. Exigirlo alli haria que
	 * <b>poder cobrar dependiera del medio de pago elegido</b> —efectivo si, tarjeta no—, que es
	 * absurdo desde el mostrador y que la matriz no dice en ninguna parte.
	 */
	public static final String CAJA_OPERATE = "caja:operate";

	private PermissionCodes() {
	}
}
