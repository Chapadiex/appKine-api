package com.akine.diferidos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ejecuta N tareas en N hilos DE VERDAD, lo mas cerca posible del mismo instante.
 *
 * <h2>Por que no alcanza con un executor y dos submit</h2>
 *
 * <p>Un {@code invokeAll} sobre un pool no garantiza nada: el primer hilo puede terminar antes
 * de que el segundo arranque, y entonces el test pasa porque la ejecucion fue SECUENCIAL, no
 * porque el bloqueo funcione. Un test asi da verde con el bloqueo removido, que es exactamente
 * lo contrario de lo que se le pide.
 *
 * <p>Por eso hay dos sincronizadores y no uno:
 * <ul>
 *   <li>un {@link CountDownLatch} de arranque, para no medir el costo de crear los hilos;</li>
 *   <li>una {@link CyclicBarrier} justo antes de la operacion: <b>ningun hilo la cruza hasta
 *       que todos llegaron</b>. La ventana entre el cruce de la barrera y la primera sentencia
 *       de la operacion es de nanosegundos, contra una operacion que tarda milisegundos.</li>
 * </ul>
 *
 * <p>Eso hace la carrera muy probable, no segura: la concurrencia no se puede forzar de forma
 * absoluta desde el proceso de test. Lo que si es seguro es la conclusion, y por eso cada test
 * que usa esta clase afirma sobre el ESTADO FINAL de la base —cuantas filas quedaron— y no
 * sobre el orden en que respondieron los hilos. Un invariante de conteo que se cumple da la
 * misma respuesta hayan corrido en paralelo o no; lo que un test asi no puede hacer es dar
 * verde con el invariante roto.
 */
final class Concurrencia {

	private Concurrencia() {
	}

	/** Desenlace de una tarea: su valor, o la excepcion con la que murio. */
	record Resultado<T>(T valor, Throwable error) {

		boolean fallo() {
			return error != null;
		}
	}

	/**
	 * Corre las tareas en paralelo y devuelve los desenlaces en el mismo orden.
	 *
	 * <p>No propaga: una tarea que lanza es un resultado valido y muchas veces el esperado
	 * —el hilo perdedor de una carrera de limite de plan lanza a proposito—.
	 */
	static <T> List<Resultado<T>> enParalelo(List<Callable<T>> tareas) {
		int cantidad = tareas.size();
		CountDownLatch listos = new CountDownLatch(cantidad);
		CyclicBarrier barrera = new CyclicBarrier(cantidad);

		try (ExecutorService pool = Executors.newFixedThreadPool(cantidad)) {
			List<Future<Resultado<T>>> futuros = new ArrayList<>(cantidad);
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					listos.countDown();
					try {
						// Nadie avanza hasta que estan todos.
						barrera.await(30, TimeUnit.SECONDS);
						return new Resultado<>(tarea.call(), null);
					} catch (Throwable e) {
						return new Resultado<T>(null, e);
					}
				}));
			}

			List<Resultado<T>> resultados = new ArrayList<>(cantidad);
			for (Future<Resultado<T>> futuro : futuros) {
				try {
					resultados.add(futuro.get(60, TimeUnit.SECONDS));
				} catch (Exception e) {
					throw new IllegalStateException("Una tarea concurrente no termino a tiempo", e);
				}
			}
			return resultados;
		}
	}
}
