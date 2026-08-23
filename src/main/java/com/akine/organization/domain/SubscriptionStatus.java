package com.akine.organization.domain;

/**
 * Estados posibles de la suscripcion de una organizacion (RF-M01-003).
 *
 * <p>La spec exige que exista un estado y que las transiciones sean validas, pero no enumera
 * los valores: este conjunto minimo es DECISION de diseno. Se eligio el minimo que satisface
 * RF-M01-003 y RN-M01-002, sin inventar trial ni morosidad: cuando aparezca la facturacion,
 * se agregan estados por expansion y con ADR propio.
 *
 * <p>La transicion de estado NO es lo mismo que el cambio de plan: cambiar de plan es una
 * operacion aparte, permitida solo con la suscripcion {@link #ACTIVA}.
 */
public enum SubscriptionStatus {

	/** Operacion completa. Unica forma de nacer de una suscripcion. */
	ACTIVA,

	/**
	 * Operacion restringida: lecturas y administracion si; mutaciones de negocio no.
	 * Suspender bloquea, jamas destruye (RN-M01-002): los historicos siguen consultables.
	 */
	SUSPENDIDA,

	/**
	 * Terminal. Los datos quedan integros y consultables por la plataforma, pero el contexto
	 * de esa organizacion deja de poder seleccionarse. Reactivar una organizacion cancelada
	 * esta fuera de alcance; si aparece el caso comercial, es un ADR nuevo.
	 */
	CANCELADA;

	/** Un estado terminal no admite ninguna transicion de salida. */
	public boolean isTerminal() {
		return this == CANCELADA;
	}
}
