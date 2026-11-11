package com.akine.knowledge.application;

import com.akine.knowledge.domain.exception.ConsultaOcupadaException;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Cola de consultas a la biblioteca de esta instancia: 1 en curso y hasta {@code maxEnEspera}
 * esperando. Con la cola llena, {@link #reservar()} lanza {@link ConsultaOcupadaException} (503).
 *
 * <p>Un monitor con una fila explicita y no un {@code Semaphore}: quien libera el lugar se lo da
 * al primero de la fila en el mismo paso, asi el orden de llegada y la posicion que ve cada uno
 * coinciden siempre. Con un semaforo justo y esperas con plazo (necesarias para el latido), un
 * turno que vuelve a esperar se pone al final de la cola del semaforo y lo pasa el que llego
 * despues.
 */
public class ColaDeConsultas {

	/** Cada cuanto se despierta un turno que espera aunque nadie le avise, para el latido. */
	static final long LATIDO_MS = 2_000;

	private final int maxEnEspera;
	private final Object monitor = new Object();
	/** Bajo {@code monitor}. */
	private Turno enCurso;
	/** Bajo {@code monitor}: los que esperan, en orden de llegada. */
	private final ArrayDeque<Turno> fila = new ArrayDeque<>();

	public ColaDeConsultas(int maxEnEspera) {
		if (maxEnEspera < 0) {
			throw new IllegalArgumentException("maxEnEspera no puede ser negativo");
		}
		this.maxEnEspera = maxEnEspera;
	}

	/**
	 * Reserva un turno sin bloquear: con el lugar libre, el turno ya lo tiene; si no, queda al final
	 * de la fila.
	 *
	 * @throws ConsultaOcupadaException si hay una consulta en curso y {@code maxEnEspera} esperando
	 */
	public Turno reservar() {
		synchronized (monitor) {
			Turno turno = new Turno();
			if (enCurso == null) {
				enCurso = turno;
			} else if (fila.size() < maxEnEspera) {
				fila.addLast(turno);
			} else {
				throw new ConsultaOcupadaException();
			}
			return turno;
		}
	}

	/** Cuantas consultas pueden esperar detras de la que esta en curso. */
	public int maxEnEspera() {
		return maxEnEspera;
	}

	/** Consultas con el lugar tomado en este momento: 0 o 1. */
	public int enCurso() {
		synchronized (monitor) {
			return enCurso == null ? 0 : 1;
		}
	}

	/** Consultas esperando el lugar en este momento. */
	public int enEspera() {
		synchronized (monitor) {
			return fila.size();
		}
	}

	/** Un lugar reservado en la cola. {@code close()} lo libera y es idempotente. */
	public final class Turno implements AutoCloseable {

		/** Bajo {@code monitor}: cerrado o abandonado; ya no espera ni ocupa. */
		private boolean afuera;

		private Turno() {
		}

		/** Igual que {@link #esperar(IntConsumer, Runnable)} sin latido. */
		public boolean esperar(IntConsumer alCambiarPosicion) {
			return esperar(alCambiarPosicion, () -> { });
		}

		/**
		 * Bloquea hasta obtener el lugar. Avisa la posicion (1 = el proximo) cada vez que cambia, y
		 * llama a {@code latido} cada {@link #LATIDO_MS} mientras espera, para que quien escribe al
		 * cliente note si se fue. Los dos se llaman fuera del monitor; si lanzan, la excepcion sale
		 * de aca y el turno sigue reservado hasta {@link #close()}.
		 *
		 * @return {@code true} con el lugar tomado; {@code false} si el turno se abandono o se cerro
		 *         mientras esperaba, o si el hilo se interrumpio
		 */
		public boolean esperar(IntConsumer alCambiarPosicion, Runnable latido) {
			int avisada = 0;
			long proximoLatido = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LATIDO_MS);
			while (true) {
				int posicion;
				synchronized (monitor) {
					if (enCurso == this) {
						return true;
					}
					if (afuera) {
						return false;
					}
					posicion = posicion();
					if (posicion == avisada) {
						long restante = TimeUnit.NANOSECONDS.toMillis(proximoLatido - System.nanoTime());
						if (restante > 0) {
							try {
								monitor.wait(restante);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								return false;
							}
							continue;
						}
					}
				}
				if (posicion != avisada) {
					avisada = posicion;
					alCambiarPosicion.accept(posicion);
				} else {
					proximoLatido = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LATIDO_MS);
					latido.run();
				}
			}
		}

		/**
		 * Desde otro hilo (el cliente se fue): si el turno todavia espera, deja la fila y
		 * {@link #esperar} vuelve con {@code false}. Con el lugar ya tomado no hace nada: lo
		 * libera {@link #close()} del hilo que corre la consulta.
		 */
		public void abandonar() {
			synchronized (monitor) {
				if (enCurso != this && !afuera) {
					afuera = true;
					fila.remove(this);
					monitor.notifyAll();
				}
			}
		}

		@Override
		public void close() {
			synchronized (monitor) {
				if (afuera) {
					return;
				}
				afuera = true;
				if (enCurso == this) {
					enCurso = fila.pollFirst();
				} else {
					fila.remove(this);
				}
				monitor.notifyAll();
			}
		}

		/** Bajo {@code monitor}: 1 = el primero de la fila. */
		private int posicion() {
			int i = 1;
			for (Turno turno : fila) {
				if (turno == this) {
					return i;
				}
				i++;
			}
			return 0;
		}
	}
}
