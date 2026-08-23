package com.akine.organization.domain;

/**
 * Estado operativo efectivo de un tenant, tal como lo ve el resto del sistema.
 *
 * <p>Es un valor CALCULADO, nunca una columna. La organizacion no tiene maquina de estados
 * propia a proposito: si el estado viviera en dos entidades, tarde o temprano existiria una
 * organizacion "activa" con la suscripcion cancelada y no habria forma de decidir quien gana.
 *
 * <p>Regla de calculo: organizacion dada de baja logica ({@code active = 0}) devuelve
 * {@link #BAJA}; en cualquier otro caso se mapea el estado de la suscripcion.
 */
public enum OperationalStatus {

	/** Tenant plenamente operativo. */
	ACTIVA,

	/** Tenant en modo restringido: lecturas y administracion, sin mutaciones de negocio. */
	SUSPENDIDA,

	/** Suscripcion terminada. El contexto de esa organizacion no puede seleccionarse. */
	CANCELADA,

	/** La organizacion en si fue dada de baja logica. Distinto de cancelar la suscripcion. */
	BAJA
}
