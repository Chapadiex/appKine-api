package com.akine.platform.spi.modelo;

import java.time.Duration;
import java.util.List;

/**
 * El servidor de modelos (Ollama) visto desde la pantalla de configuracion: si responde, que
 * modelos usa cada funcion, si estan descargados y cargados, y si corren en GPU. No cambia la
 * configuracion del despliegue: solo la lee y ejercita.
 */
public interface MotorDeModelos {

	/** Nunca lanza: un motor caido vuelve con {@code alcanzable = false} y el motivo. */
	EstadoDelMotor estado(Duration plazo);

	/** Pide cargar cada modelo configurado y devuelve el estado despues. Nunca lanza. */
	EstadoDelMotor precargar(Duration plazo);

	/** Una pregunta minima al modelo del asistente, con el tiempo que tardo. Nunca lanza. */
	PruebaDelMotor probar(Duration plazo);

	/**
	 * @param url base del servidor; nula si ninguna funcion usa Ollama en este despliegue
	 * @param error motivo legible si no se pudo consultar; nulo si respondio
	 */
	record EstadoDelMotor(boolean alcanzable, String url, String error, List<ModeloDelMotor> modelos) {
	}

	/**
	 * @param funciones funciones que usan este modelo ({@code ASISTENTE}, {@code SUGERENCIAS},
	 *        {@code BIBLIOTECA})
	 * @param memoriaMb memoria que ocupa cargado; 0 si no esta cargado
	 * @param memoriaGpuMb de esa memoria, cuanta esta en la placa de video
	 */
	record ModeloDelMotor(String nombre, List<String> funciones, boolean descargado, boolean cargado,
			long memoriaMb, long memoriaGpuMb) {

		/** GPU si todo el modelo esta en la placa, CPU si nada, MIXTO si se partio; nulo si no esta cargado. */
		public String ejecucion() {
			if (!cargado || memoriaMb == 0) {
				return null;
			}
			if (memoriaGpuMb >= memoriaMb) {
				return "GPU";
			}
			return memoriaGpuMb == 0 ? "CPU" : "MIXTO";
		}
	}

	/**
	 * @param ok si el modelo contesto
	 * @param milisegundos cuanto tardo, incluida la carga si no estaba cargado
	 */
	record PruebaDelMotor(boolean ok, String modelo, long milisegundos, String error) {
	}
}
