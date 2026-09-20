package com.akine.reporting.spi;

/**
 * Los reportes del MVP (M23).
 *
 * <p>Uno por RF: {@code OPERATIVO} es RF-M23-001, {@code TURNOS} RF-M23-002, {@code CLINICO}
 * RF-M23-003, {@code ECONOMICO} RF-M23-004 y {@code FINANCIADORES} RF-M23-005. RF-M23-006 —el
 * export— no es un reporte mas: es otra representacion de cualquiera de estos cinco.
 *
 * <p><b>Los de la segunda entrega no estan y no deben agregarse vacios.</b> RF-M23-007 a
 * RF-M23-010 dependen de {@code activity} (M28/M29), que no existe. Un reporte que muestra cero
 * porque su fuente no existe es peor que un reporte ausente: el operador no distingue "no hubo" de
 * "no lo se".
 *
 * <p>El nombre viaja al cliente en minusculas dentro de la ruta, asi que renombrar un valor es un
 * cambio de contrato aunque no toque ningun DTO.
 */
public enum ReporteCode {

	/** Tablero del dia a dia de la sede: turnos, sesiones y la situacion economica gruesa. */
	OPERATIVO,

	/** Volumen, estados, ausentismo y reprogramaciones. */
	TURNOS,

	/** Casos y sesiones, agregados y sin contenido clinico. */
	CLINICO,

	/** Los cinco conceptos economicos, separados y sin ningun total que los sume. */
	ECONOMICO,

	/** Prestado, presentado, facturado, cobrado y pendiente por financiador. */
	FINANCIADORES
}
