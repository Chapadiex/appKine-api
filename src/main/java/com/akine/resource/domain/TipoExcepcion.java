package com.akine.resource.domain;

/**
 * Sentido de una {@link DisponibilidadExcepcion} (RF-M05-004).
 *
 * <p>{@code CIERRE} tapa una franja que el horario base o una apertura habian dejado abierta.
 * {@code APERTURA} hace lo contrario: habilita atencion donde el horario base, un feriado o
 * {@code consultorio_calendario.cierraPorFeriado} la hubieran negado. Ninguna de las dos es el
 * caso raro: RF-M05-004 pide explicitamente "ausencia, licencia, bloqueo o ampliacion
 * excepcional", y la ampliacion es una apertura.
 *
 * <p>Los valores replican exactamente {@code ck_disponibilidad_excepcion_tipo} de V23. Agregar
 * uno exige migracion.
 */
public enum TipoExcepcion {

	/** Recorta disponibilidad que de otro modo estaria abierta. */
	CIERRE,

	/** Habilita disponibilidad que de otro modo estaria cerrada. */
	APERTURA
}
