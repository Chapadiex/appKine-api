package com.akine.organization.domain;

/**
 * Maquina de estados de la membership (§33, RN-M05-003).
 *
 * <p><b>Es una tercera condicion, no un reemplazo.</b> Una membership habilita algo solo si
 * cumple las tres: {@code active} (baja logica), la ventana de vigencia
 * ({@code valid_from}/{@code valid_until}) y este estado. Colapsarlas dejaria indistinguibles
 * "suspendida" de "revocada", que es justamente la diferencia entre un vinculo que puede
 * volver y uno que murio.
 *
 * <p><b>{@code INVITADA} y {@code RECHAZADA} no existen todavia</b>, y no es un olvido: eran
 * los estados del flujo de invitacion por mail, que la decision D-1 dejo fuera de AKINE-01.03.
 * Declarar valores que ninguna transicion produce seria documentar una maquina de estados que
 * no existe. Cuando la invitacion llegue, agregarlos es una migracion de catalogo.
 */
public enum MembershipEstado {

	/** Vinculo vigente. Es el unico estado que habilita algo. */
	ACTIVA,

	/** Suspendido temporalmente. No habilita, y puede volver a {@link #ACTIVA}. */
	SUSPENDIDA,

	/**
	 * Vinculo terminado. Estado TERMINAL: no hay transicion de salida.
	 *
	 * <p>La fila no se borra nunca (RN-M05-003, regla maestra 10): revocar cierra la vigencia,
	 * marca la baja logica y deja constancia de quien revoco y por que. Revocar no borra
	 * autoria.
	 */
	REVOCADA;

	/** Indica si desde este estado se puede pasar al destino. */
	public boolean puedePasarA(MembershipEstado destino) {
		return switch (this) {
			case ACTIVA -> destino == SUSPENDIDA || destino == REVOCADA;
			case SUSPENDIDA -> destino == ACTIVA || destino == REVOCADA;
			case REVOCADA -> false;
		};
	}

	/** Indica si el vinculo, por su estado, puede habilitar permisos. */
	public boolean habilita() {
		return this == ACTIVA;
	}
}
