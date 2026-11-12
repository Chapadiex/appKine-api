package com.akine.knowledge.domain.port;

/**
 * Cantidades y tiempos de una generacion, como los informa el servidor del modelo en la ultima
 * respuesta del stream. Sin texto: van al log y a las metricas. Todo en cero si el servidor no los
 * informo (cancelacion, modelo de prueba).
 *
 * @param tokensEntrada tokens del prompt evaluados ({@code prompt_eval_count} de Ollama)
 * @param nanosEntrada tiempo de evaluar el prompt ({@code prompt_eval_duration})
 * @param tokensSalida tokens generados ({@code eval_count})
 * @param nanosSalida tiempo de generarlos ({@code eval_duration})
 */
public record EstadisticasDeGeneracion(long tokensEntrada, long nanosEntrada, long tokensSalida, long nanosSalida) {

	public static final EstadisticasDeGeneracion NINGUNA = new EstadisticasDeGeneracion(0, 0, 0, 0);

	/** Tokens generados por segundo; 0 si no hay datos. */
	public double tokensPorSegundo() {
		return nanosSalida <= 0 ? 0d : tokensSalida * 1_000_000_000d / nanosSalida;
	}
}
