package com.akine.reporting.spi;

/**
 * Los reportes del MVP (M23).
 *
 * <p>Uno por RF: {@code OPERATIVO} es RF-M23-001, {@code TURNOS} RF-M23-002, {@code CLINICO}
 * RF-M23-003, {@code ECONOMICO} RF-M23-004 y {@code FINANCIADORES} RF-M23-005. RF-M23-006 —el
 * export— no es un reporte mas: es otra representacion de cualquiera de estos cinco.
 *
 * <p><b>Los de la segunda entrega entran con su fuente, nunca vacios</b> (AKINE-09.01):
 * {@code CLASES} es RF-M23-007 y {@code PASES} es RF-M23-010, los dos alimentados por
 * {@code activity} (M28/M29). {@code CONTINUIDAD} es RF-M23-008 y {@code SERVICIOS} es
 * RF-M23-009 (AKINE-09.01b): los ingresos por servicio y, desde DP-39, la rentabilidad (ingresos
 * menos egresos imputados a cada oferta) y la caja por servicio. Sin ningun egreso imputado el
 * reporte lo declara en una advertencia en vez de mostrar como margen lo que es facturacion. Un
 * reporte que muestra cero porque su fuente no existe es peor que un reporte ausente: el operador no distingue "no hubo" de "no lo se".
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
	FINANCIADORES,

	/**
	 * Ocupacion de clases y espacios (RF-M23-007): clases, inscriptos, lista de espera,
	 * asistentes y ausentes. Una asistencia no es una sesion clinica (RN-M23-005).
	 */
	CLASES,

	/**
	 * Estado de pases y abonos (RF-M23-010): ventas, movimientos de creditos, vencimientos,
	 * anulaciones y renovaciones. Un credito no es dinero (RN-M23-006).
	 */
	PASES,

	/**
	 * Continuidad del circuito de salud (RF-M23-008): cuantas personas con alta clinica en el
	 * periodo pasan despues a un servicio de cada naturaleza, sin contenido clinico ni personas.
	 */
	CONTINUIDAD,

	/**
	 * Ingresos por servicio (RF-M23-009): devengado y cobrado por oferta, separando prestaciones,
	 * clases, packs y abonos, y los creditos consumidos aparte, que no son ingreso; la rentabilidad
	 * por oferta y lo cobrado en caja repartido por servicio (DP-39).
	 */
	SERVICIOS
}
