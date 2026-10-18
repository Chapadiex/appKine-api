package com.akine.activity.application;

import com.akine.activity.domain.exception.LotesDeAsistenciaSaturadosException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cuantos lotes de asistencia pueden correr a la vez en una misma sede (U-49 opcion c, DP-30).
 *
 * <p><b>Apagado por defecto.</b> Con {@value #PROPIEDAD} ausente o en {@code 0} no hay semaforo, no
 * hay espera y el lote corre exactamente como antes. DP-30 lo condiciona al entorno de destino: se
 * enciende solo si ahi el lote satura el pool (docs/carga.md §12, "U-49 c").
 *
 * <p>Con {@code N > 0}, un semaforo <b>justo</b> por sede deja pasar hasta {@code N} lotes a la vez.
 * El que no consigue lugar espera hasta {@value #PROPIEDAD_ESPERA} (2 s por defecto) y, si no, se
 * rechaza con {@link LotesDeAsistenciaSaturadosException}: 503 con {@code Retry-After}.
 *
 * <p><b>El semaforo es en memoria y por instancia.</b> Con varias instancias detras de un balanceador
 * el techo efectivo por sede es {@code N x instancias}. Es intencional: lo que protege es el pool de
 * conexiones de cada instancia, que tambien es por instancia.
 *
 * <p><b>No toca transacciones, locks ni el resultado por item</b>: se toma antes del primer item, sin
 * conexion abierta (el lote no es transaccional y {@code open-in-view} esta apagado), y se suelta
 * despues del ultimo. Un lote rechazado no escribio nada.
 *
 * <p>Metricas, sin tags de sede (cardinalidad): {@code akine.asistencia.lotes} por
 * {@code resultado} = {@code inmediato}, {@code tras-espera} o {@code rechazado}, y el gauge
 * {@code akine.asistencia.lotes.en.espera}. Se registran en cero aunque el limite este apagado,
 * para que una alerta tenga serie antes del primer rechazo.
 */
@Component
public class LimiteDeLotesPorSede {

	public static final String PROPIEDAD = "akine.activity.asistencia.lotes-concurrentes-por-sede";
	public static final String PROPIEDAD_ESPERA = "akine.activity.asistencia.lotes-espera-maxima";

	private final int lugaresPorSede;
	private final Duration esperaMaxima;
	private final long segundosParaReintentar;
	private final ConcurrentMap<Long, Semaphore> semaforos = new ConcurrentHashMap<>();
	private final AtomicInteger enEspera = new AtomicInteger();
	private final Counter inmediatos;
	private final Counter trasEspera;
	private final Counter rechazados;

	@Autowired
	public LimiteDeLotesPorSede(
			@Value("${" + PROPIEDAD + ":0}") int lugaresPorSede,
			@Value("${" + PROPIEDAD_ESPERA + ":2s}") Duration esperaMaxima,
			MeterRegistry metricas) {

		if (lugaresPorSede < 0) {
			throw new IllegalArgumentException(PROPIEDAD + " no admite negativos: " + lugaresPorSede);
		}
		if (esperaMaxima.isNegative()) {
			throw new IllegalArgumentException(PROPIEDAD_ESPERA + " no admite negativos: " + esperaMaxima);
		}
		this.lugaresPorSede = lugaresPorSede;
		this.esperaMaxima = esperaMaxima;
		// Retry-After solo admite segundos enteros: se redondea hacia arriba y nunca es 0, que
		// seria "reintente ya" y volveria a chocar.
		this.segundosParaReintentar = Math.max(1L, (esperaMaxima.toMillis() + 999) / 1000);
		this.inmediatos = contador(metricas, "inmediato");
		this.trasEspera = contador(metricas, "tras-espera");
		this.rechazados = contador(metricas, "rechazado");
		Gauge.builder("akine.asistencia.lotes.en.espera", enEspera, AtomicInteger::get)
				.description("Lotes de asistencia esperando lugar en el limite por sede")
				.register(metricas);
	}

	/** Sin limite: para los tests que construyen el servicio a mano. */
	public static LimiteDeLotesPorSede sinLimite() {
		return new LimiteDeLotesPorSede(0, Duration.ZERO, new SimpleMeterRegistry());
	}

	public boolean activo() {
		return lugaresPorSede > 0;
	}

	/**
	 * Toma un lugar de la sede, esperando hasta la espera maxima. Se suelta con
	 * {@link Permiso#close()}, idealmente en un try-with-resources.
	 *
	 * @throws LotesDeAsistenciaSaturadosException si no hubo lugar a tiempo (o el hilo se
	 *         interrumpio esperando)
	 */
	public Permiso tomar(long consultorioId) {
		if (!activo()) {
			return Permiso.NINGUNO;
		}
		Semaphore semaforo = semaforos.computeIfAbsent(
				consultorioId, id -> new Semaphore(lugaresPorSede, true));

		// tryAcquire() sin tiempo se cuela delante de la cola aun en un semaforo justo; con
		// tiempo cero respeta el orden de llegada.
		if (intentar(semaforo, 0)) {
			inmediatos.increment();
			return permisoDe(semaforo);
		}
		enEspera.incrementAndGet();
		boolean conseguido;
		try {
			conseguido = intentar(semaforo, esperaMaxima.toNanos());
		} finally {
			enEspera.decrementAndGet();
		}
		if (!conseguido) {
			rechazados.increment();
			throw new LotesDeAsistenciaSaturadosException(consultorioId, segundosParaReintentar);
		}
		trasEspera.increment();
		return permisoDe(semaforo);
	}

	private static boolean intentar(Semaphore semaforo, long nanos) {
		try {
			return semaforo.tryAcquire(nanos, TimeUnit.NANOSECONDS);
		} catch (InterruptedException interrupcion) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Soltar dos veces no puede regalar un lugar: el segundo close no hace nada. */
	private static Permiso permisoDe(Semaphore semaforo) {
		AtomicBoolean suelto = new AtomicBoolean();
		return () -> {
			if (suelto.compareAndSet(false, true)) {
				semaforo.release();
			}
		};
	}

	private static Counter contador(MeterRegistry metricas, String resultado) {
		return Counter.builder("akine.asistencia.lotes")
				.description("Lotes de asistencia por resultado del limite por sede")
				.tag("resultado", resultado)
				.register(metricas);
	}

	/** Un lugar tomado. {@code close()} no lanza. */
	@FunctionalInterface
	public interface Permiso extends AutoCloseable {

		/** Ningun lugar tomado: el limite esta apagado, o el pedido no pasa por el. */
		Permiso NINGUNO = () -> { };

		@Override
		void close();
	}
}
