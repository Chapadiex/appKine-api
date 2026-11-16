package com.akine.platform.spi.modelo;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Solo para tests: un {@link ModeloDeLenguaje} que se escribe como lambda de {@code completarJson}.
 * Las otras dos operaciones no se usan donde se pone este doble.
 */
@FunctionalInterface
public interface ModeloSoloJson extends ModeloDeLenguaje {

	@Override
	default String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		throw new UnsupportedOperationException("completar");
	}

	@Override
	default EstadisticasDeGeneracion generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones,
			Duration plazo, Consumer<String> alFragmento, BooleanSupplier cancelado) {
		throw new UnsupportedOperationException("generar");
	}

	/** Delega en la de tres argumentos: las opciones no cambian lo que contesta el doble. */
	@Override
	default String completarJson(List<MensajeChat> mensajes, String esquemaJson, OpcionesGeneracion opciones,
			Duration plazo) {
		return completarJson(mensajes, esquemaJson, plazo);
	}
}
