package com.akine.identity.domain;

/**
 * Estados posibles de una cuenta (RF-M02-002 "validar estado", RF-M02-005 "bloquear o
 * desactivar").
 *
 * <p>Las transiciones no viven en el enum sino en {@link AccountStateMachine}: el enum
 * enumera, la maquina decide. Mezclar las dos cosas obliga a tocar el enum cada vez que
 * cambia una regla.
 */
public enum EstadoCuenta {

	/**
	 * La cuenta existe pero todavia no puede entrar: falta que la persona confirme el
	 * enlace que se le envio. Nace asi tanto el alta self-service como la invitacion.
	 */
	PENDIENTE_ACTIVACION,

	/** Unico estado que habilita el login. */
	ACTIVA,

	/**
	 * Suspension administrativa REVERSIBLE: sospecha de compromiso, deuda, medida
	 * disciplinaria. Corta las sesiones vivas y no destruye nada.
	 */
	BLOQUEADA,

	/**
	 * Baja logica definitiva. Estado TERMINAL: {@code active = 0} y {@code deleted_at}
	 * seteado, pero la fila se conserva para que los historicos que la referencian sigan
	 * siendo legibles (RN-M02-004). Volver de aca es una operacion de soporte, fuera del
	 * alcance de la aplicacion.
	 */
	DESACTIVADA;

	/** Indica si el estado habilita autenticarse. Solo {@link #ACTIVA} lo hace. */
	public boolean permiteLogin() {
		return this == ACTIVA;
	}
}
