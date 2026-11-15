package com.akine.platform.spi.modelo;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Puerto del modelo de lenguaje local (Ollama), compartido por los modulos que lo usan (D-3). Cada
 * consumidor arma su instancia con {@link FabricaDeModelos} desde sus propias propiedades, y traduce
 * {@link ModeloNoDisponibleException} a su problema tipado.
 */
public interface ModeloDeLenguaje {

	/**
	 * Respuesta completa.
	 *
	 * @throws ModeloNoDisponibleException si el servidor no responde, responde con error o vence el
	 *         plazo
	 */
	String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo);

	/**
	 * Emite fragmentos a medida que llegan; corta si {@code cancelado} pasa a true. Devuelve las
	 * estadisticas que informo el servidor, o {@link EstadisticasDeGeneracion#NINGUNA}.
	 *
	 * @throws ModeloNoDisponibleException si el servidor no responde o vence el plazo
	 */
	EstadisticasDeGeneracion generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado);

	/**
	 * El texto que devolvio el modelo en modo JSON, sin validar: puede no ser JSON valido y lo valida
	 * quien lo pide. Con {@code esquemaJson} no nulo se le pasa ese esquema en {@code format}; nulo,
	 * {@code format: "json"}.
	 *
	 * @throws ModeloNoDisponibleException si el servidor no responde, responde con error o vence el
	 *         plazo
	 */
	String completarJson(List<MensajeChat> mensajes, String esquemaJson, Duration plazo);
}
