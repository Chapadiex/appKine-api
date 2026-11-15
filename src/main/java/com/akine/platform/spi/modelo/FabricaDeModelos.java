package com.akine.platform.spi.modelo;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Arma el modelo de lenguaje de cada consumidor desde su propia configuracion. Existe porque los
 * modulos solo alcanzan a {@code platform} por su {@code spi}: no pueden crear el adaptador.
 */
public interface FabricaDeModelos {

	/**
	 * Un modelo nuevo segun {@link ConfiguracionDeModelo#tipo()}, envuelto en un cupo si
	 * {@link ConfiguracionDeModelo#concurrencia()} es mayor que cero.
	 *
	 * @throws IllegalArgumentException si el tipo no es {@code ollama} ni {@code prueba}
	 */
	ModeloDeLenguaje crear(ConfiguracionDeModelo configuracion);

	/**
	 * Pide a Ollama cargar el modelo de esa configuracion, en segundo plano. El futuro termina con el
	 * status HTTP, o con la excepcion si no responde. Para el tipo {@code prueba} termina con 200.
	 */
	CompletableFuture<Integer> precargar(ConfiguracionDeModelo configuracion, Duration plazo);
}
