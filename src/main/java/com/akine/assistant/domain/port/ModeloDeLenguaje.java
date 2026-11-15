package com.akine.assistant.domain.port;

import java.time.Duration;
import java.util.List;

/**
 * Puerto del modelo que interpreta las preguntas del asistente.
 *
 * <p>Una sola operacion: devuelve un objeto JSON y nada mas. El asistente no le pide redactar ni le
 * pasa datos del centro (diseño, §4.1). Es una copia acotada del puerto de {@code knowledge}
 * (D-3): el tercer consumidor lo extrae a {@code platform.spi}.
 */
public interface ModeloDeLenguaje {

	/**
	 * El texto que devolvio el modelo en modo JSON, sin validar. Puede no ser JSON valido: lo valida
	 * quien lo pide.
	 *
	 * @throws com.akine.assistant.domain.exception.ModeloNoDisponibleException si el servidor no
	 *         responde, responde con error o vence el plazo
	 */
	String completarJson(List<MensajeChat> mensajes, Duration plazo);
}
