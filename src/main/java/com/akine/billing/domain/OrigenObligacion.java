package com.akine.billing.domain;

/**
 * Que hecho devengo la deuda (V64 y V107, {@code ck_obligacion_origen}).
 *
 * <p>{@code SESION} es la prestacion de 07.01 y lleva {@code sesion_id}; {@code VENTA_PASE} es la
 * compra de un pack de 08.06 (RF-M18-009) y lleva {@code pase_id}. {@code VENTA_ABONO} esta en el
 * CHECK desde V64 y la escribe la venta de un abono (08.08, RF-M18-010): lleva {@code pase_id}
 * igual, porque el abono es un pase con periodo. {@code ASISTENCIA_CLASE} es la clase asistida de
 * una oferta {@code POR_CLASE} (U-39, RF-M18-008, V107): lleva {@code asistencia_id} y
 * {@code clase_id}.
 */
public enum OrigenObligacion {
	SESION,
	VENTA_PASE,
	VENTA_ABONO,
	ASISTENCIA_CLASE;

	/** La deuda de una venta: se anula por el pase, nunca sola (AKINE-08.09). */
	public boolean esDeVenta() {
		return this == VENTA_PASE || this == VENTA_ABONO;
	}
}
