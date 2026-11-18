package com.akine.encounter.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Un pedido de sugerencias de evolucion a la vez por cuenta, en esta instancia (diseno fase 2, §8.1).
 *
 * <p>Sin tabla ni lock de base, como {@code ColaDeConsultas} de la biblioteca: lo que protege es el
 * modelo local de esta instancia, no un dato. Con varias instancias, una cuenta podria tener un pedido
 * en cada una; el cupo del modelo sigue acotando lo que llega a Ollama.
 */
@Component
class CandadoPorCuenta {

	private final ConcurrentHashMap<Long, Boolean> tomados = new ConcurrentHashMap<>();

	/** {@code true} si la cuenta no tenia el candado y ahora lo tiene. */
	boolean tomar(long cuentaId) {
		return tomados.putIfAbsent(cuentaId, Boolean.TRUE) == null;
	}

	void soltar(long cuentaId) {
		tomados.remove(cuentaId);
	}
}
