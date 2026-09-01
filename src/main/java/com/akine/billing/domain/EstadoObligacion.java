package com.akine.billing.domain;

/**
 * En que anda la deuda.
 *
 * <p>Los tres primeros se DERIVAN del saldo y no se declaran a mano: mantener un estado que puede
 * contradecir al saldo es tener dos fuentes de verdad para el mismo hecho. {@link #ANULADA} es el
 * unico que no se deriva, porque es una decision y no una consecuencia.
 */
public enum EstadoObligacion {

	/** Saldo igual al importe: no se imputo nada todavia. */
	PENDIENTE,

	/** Saldo mayor que cero y menor que el importe. */
	PARCIAL,

	/** Saldo cero por cobros imputados. Distinto de ANULADA: aca la plata entro. */
	PAGADA,

	/** Anulada con motivo. No se borra: una deuda que desaparece es una cuenta que no cuadra. */
	ANULADA
}
