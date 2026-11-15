package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.EstadisticasDeGeneracion;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.ModeloOcupadoException;
import com.akine.platform.spi.modelo.OpcionesGeneracion;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Limita cuantas llamadas esperan al modelo a la vez. Ollama atiende de a pocas (por defecto una o
 * pocas en paralelo): sin un tope, cada llamada de mas retiene un hilo del servidor hasta vencer su
 * plazo y las ultimas fallan igual, despues de esperar. Con el tope, la que no consigue lugar dentro
 * de su plazo lanza {@link ModeloOcupadoException} sin tomar el hilo por mas tiempo.
 *
 * <p>El cupo cuenta para esta instancia del backend. Con varias instancias contra el mismo Ollama, el
 * tope efectivo es la suma: lo ajusta la concurrencia de la configuracion de cada consumidor.
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
	public String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		return conCupo(plazo, restante -> modelo.completar(mensajes, opciones, restante));
	}

	@Override
	public EstadisticasDeGeneracion generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado) {
		return conCupo(plazo, restante -> modelo.generar(mensajes, opciones, restante, alFragmento, cancelado));
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, String esquemaJson, Duration plazo) {
		return conCupo(plazo, restante -> modelo.completarJson(mensajes, esquemaJson, restante));
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, String esquemaJson, OpcionesGeneracion opciones,
			Duration plazo) {
		return conCupo(plazo, restante -> modelo.completarJson(mensajes, esquemaJson, opciones, restante));
	}

	private <T> T conCupo(Duration plazo, Function<Duration, T> llamada) {
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
			throw new ModeloOcupadoException();
		}
		try {
			// Lo que se espero en la fila se descuenta del plazo de la llamada.
			Duration restante = plazo.minusNanos(System.nanoTime() - inicio);
			if (restante.isNegative() || restante.isZero()) {
				throw new ModeloNoDisponibleException();
			}
			return llamada.apply(restante);
		}
		finally {
			cupos.release();
		}
	}
}
