package com.akine.platform.spi.modelo;

/**
 * Lo que necesita {@link FabricaDeModelos} para armar el modelo de un consumidor. Sale de las
 * propiedades de cada modulo, que no se unifican.
 *
 * @param tipo {@code ollama} o {@code prueba}
 * @param url base del servidor de Ollama (sin {@code /api/chat})
 * @param nombre nombre del modelo en Ollama
 * @param keepAlive cuanto lo mantiene Ollama cargado despues de cada llamada ({@code keep_alive})
 * @param concurrencia cuantas llamadas esperan al modelo a la vez en esta instancia; 0 = sin cupo
 * @param jsonDePrueba lo que contesta {@code completarJson} el modelo de prueba por defecto; nulo, el
 *        ultimo mensaje de usuario. Ignorado con {@code ollama}
 */
public record ConfiguracionDeModelo(String tipo, String url, String nombre, String keepAlive, int concurrencia,
		String jsonDePrueba) {

	public static final String OLLAMA = "ollama";
	public static final String PRUEBA = "prueba";

	public ConfiguracionDeModelo {
		if (concurrencia < 0) {
			throw new IllegalArgumentException("La concurrencia del modelo no puede ser negativa");
		}
	}
}
