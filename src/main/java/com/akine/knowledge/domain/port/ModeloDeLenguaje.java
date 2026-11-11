package com.akine.knowledge.domain.port;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Puerto del modelo de lenguaje local que redacta las respuestas de la biblioteca. */
public interface ModeloDeLenguaje {

	/**
	 * Respuesta completa.
	 *
	 * @throws com.akine.knowledge.domain.exception.AsistenteNoDisponibleException si el servidor
	 *         no responde o vence el plazo
	 */
	String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo);

	/** Emite fragmentos a medida que llegan; corta si {@code cancelado} pasa a true. */
	void generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado);
}
