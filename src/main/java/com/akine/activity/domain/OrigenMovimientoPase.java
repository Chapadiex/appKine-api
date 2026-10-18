package com.akine.activity.domain;

/**
 * Que hecho produjo un movimiento de credito ({@code ck_movimiento_pase_origen_tipo}, V64).
 *
 * <p>{@code VENTA}: el {@code origen_id} es el propio pase. {@code ASISTENCIA}: el
 * {@code origen_id} es el <b>evento</b> de asistencia (registro o correccion) que lo produjo, y
 * {@code asistencia_id} (V96) dice de que asistencia es. Asi una asistencia corregida dos veces
 * puede consumir, devolver y volver a consumir sin chocar contra {@code uk_movimiento_origen}.
 */
public enum OrigenMovimientoPase {
	VENTA,
	ASISTENCIA,
	CANCELACION,
	AJUSTE_MANUAL,
	VENCIMIENTO
}
