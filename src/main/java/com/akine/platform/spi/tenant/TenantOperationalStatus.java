package com.akine.platform.spi.tenant;

/**
 * Estado operativo de un tenant, visto desde {@code platform}.
 *
 * <p><b>Por que existe una copia de este enum en {@code platform.spi}.</b> El estado operativo
 * lo calcula {@code organization} a partir de su suscripcion, pero {@code platform} tiene que
 * poder razonar sobre el sin compilar contra {@code organization}: si el filtro de contexto
 * importara {@code com.akine.organization.domain.OperationalStatus} se cerraria el ciclo
 * {@code platform -> organization -> platform} y {@code sin_ciclos_entre_modulos} rechazaria el
 * build. La direccion permitida es una sola: {@code organization -> platform.spi}.
 *
 * <p>El adaptador de {@code organization.infrastructure.tenant} traduce su enum de dominio a
 * este. La traduccion es explicita y esta cubierta por test: si alguien agrega un estado nuevo
 * del lado de {@code organization} y no lo mapea, el {@code switch} exhaustivo no compila.
 */
public enum TenantOperationalStatus {

	/** Tenant plenamente operativo: lecturas y mutaciones de negocio permitidas. */
	ACTIVA,

	/** Modo restringido: lecturas y administracion si, mutaciones de negocio no. */
	SUSPENDIDA,

	/** Suscripcion terminada. El contexto de esa organizacion no puede usarse. */
	CANCELADA,

	/** La organizacion fue dada de baja logica. Distinto de cancelar la suscripcion. */
	BAJA;

	/** Indica si el tenant admite que se seleccione y use su contexto de trabajo. */
	public boolean allowsContextUsage() {
		return this == ACTIVA || this == SUSPENDIDA;
	}

	/** Indica si el tenant admite mutaciones de negocio. */
	public boolean allowsBusinessMutations() {
		return this == ACTIVA;
	}
}
