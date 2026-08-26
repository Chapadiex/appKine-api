package com.akine.resource.domain;

/**
 * Motivo declarado de una {@link DisponibilidadExcepcion} (RF-M05-004).
 *
 * <p>Lista cerrada, no texto libre, para que un reporte pueda agrupar por motivo sin normalizar
 * nada despues. Los valores replican exactamente {@code ck_disponibilidad_excepcion_motivo} de
 * V23. Agregar uno exige migracion: el conjunto es del producto, no del tenant.
 */
public enum MotivoExcepcion {

	/** Falta puntual no programada. */
	AUSENCIA,

	/** Ausencia programada y autorizada de antemano. */
	LICENCIA,

	/**
	 * La excepcion nace de un feriado del calendario nacional ({@link Feriado}). Suele venir
	 * acompanada de {@code feriadoId} para trazar el origen.
	 */
	FERIADO,

	/** Cierre administrativo sin relacion con una ausencia del profesional. */
	BLOQUEO,

	/** Apertura excepcional que suma atencion fuera del horario base. */
	AMPLIACION,

	/** Cualquier otro motivo que no encaja en los anteriores. */
	OTRO
}
