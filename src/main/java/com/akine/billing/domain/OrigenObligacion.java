package com.akine.billing.domain;

/**
 * Que hecho devengo la deuda (V64, {@code ck_obligacion_origen}).
 *
 * <p>{@code SESION} es la prestacion de 07.01 y lleva {@code sesion_id}; {@code VENTA_PASE} es la
 * compra de un pack de 08.06 (RF-M18-009) y lleva {@code pase_id}. {@code VENTA_ABONO} esta en el
 * CHECK desde V64 y la escribe la venta de un abono (08.08, RF-M18-010): lleva {@code pase_id}
 * igual, porque el abono es un pase con periodo.
 */
public enum OrigenObligacion {
	SESION,
	VENTA_PASE,
	VENTA_ABONO
}
