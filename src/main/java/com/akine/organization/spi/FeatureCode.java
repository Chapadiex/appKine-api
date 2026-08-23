package com.akine.organization.spi;

/**
 * Funcionalidades que un plan habilita o no para una organizacion (RF-M01-004).
 *
 * <p>La habilitacion se representa por PRESENCIA de una fila activa en {@code plan_feature},
 * no por un booleano: agregar una feature nueva no cambia el esquema y un plan viejo no
 * hereda por omision algo que nadie decidio darle.
 *
 * <p>Vive en {@code spi} por el mismo motivo que {@link LimitCode}: quien pregunta "puedo
 * usar esto" es siempre otro modulo.
 */
public enum FeatureCode {

	/** Reportes analiticos mas alla de los listados operativos basicos. */
	REPORTES_AVANZADOS,

	/** Envio de notificaciones al paciente (recordatorios de turno y similares). */
	NOTIFICACIONES_PACIENTE
}
