package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.ModeloDeLenguaje;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Limita cuantas consultas esperan al modelo a la vez. Ollama atiende de a pocas (por defecto una o
 * pocas en paralelo): sin un tope, cada pregunta de mas retiene un hilo del servidor hasta vencer su
 * plazo y las ultimas fallan igual, despues de esperar. Con el tope, la que no consigue lugar dentro
 * de su plazo responde 503 {@code asistente-no-disponible} sin tomar el hilo por mas tiempo.
 *
 * <p>El cupo cuenta para esta instancia del backend. Con varias instancias contra el mismo Ollama, el
 * tope efectivo es la suma: se ajusta con {@code akine.asistente.modelo.concurrencia}.
 */
public class ModeloConCupo implements ModeloDeLenguaje {

	private final ModeloDeLenguaje modelo;
	private final Semaphore cupos;

	public ModeloConCupo(ModeloDeLenguaje modelo, int concurrencia) {
		if (concurrencia < 1) {
			throw new IllegalArgumentException("La concurrencia del modelo tiene que ser al menos 1");
		}
		this.modelo = Objects.requireNonNull(modelo, "modelo");
		this.cupos = new Semaphore(concurrencia, true);
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, Duration plazo) {
		long inicio = System.nanoTime();
		boolean conCupo;
		try {
			conCupo = cupos.tryAcquire(plazo.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ModeloNoDisponibleException(e);
		}
		if (!conCupo) {
			throw new ModeloNoDisponibleException();
		}
		try {
			// Lo que se espero en la fila se descuenta del plazo de la llamada.
			Duration restante = plazo.minusNanos(System.nanoTime() - inicio);
			if (restante.isNegative() || restante.isZero()) {
				throw new ModeloNoDisponibleException();
			}
			return modelo.completarJson(mensajes, restante);
		}
		finally {
			cupos.release();
		}
	}
}
