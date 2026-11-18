package com.akine.organization.spi;

/**
 * Funciones de inteligencia artificial que un centro puede apagar para si mismo.
 *
 * <p>{@link #SUGERENCIAS} depende de las otras dos clinicas: busca en la biblioteca y parte del
 * analisis de evolucion. Si el centro apaga cualquiera de ellas, las sugerencias tambien quedan
 * apagadas.
 */
public enum FuncionIa {

	/** Asistente de datos ({@code /asistente}). */
	ASISTENTE,

	/** Alertas e indicadores de evolucion del caso (M31, fase 1). */
	EVOLUCION,

	/** Sugerencias de revision del tratamiento con citas de la biblioteca (M31, fase 2). */
	SUGERENCIAS,

	/** Biblioteca del centro y su consulta (M30). */
	BIBLIOTECA
}
