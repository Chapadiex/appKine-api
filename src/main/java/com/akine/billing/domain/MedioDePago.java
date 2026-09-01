package com.akine.billing.domain;

/**
 * Como entro la plata.
 *
 * <p>{@link #OTRO} existe y no es pereza: un centro chico cobra por transferencias de billeteras
 * virtuales, cheques o descuentos de convenio que no valen una columna propia. Sin este valor, el
 * operador elegiria el medio equivocado —normalmente EFECTIVO— y el arqueo de caja no cerraria por
 * una razon que nadie podria rastrear. La referencia libre es donde se anota que fue.
 */
public enum MedioDePago {
	EFECTIVO,
	TRANSFERENCIA,
	TARJETA_DEBITO,
	TARJETA_CREDITO,
	OTRO
}
