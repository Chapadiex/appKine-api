package com.akine.knowledge.infrastructure;

import com.akine.knowledge.infrastructure.config.BibliotecaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Hilo propio de la biblioteca: procesa libros y reindexa en UN hilo dedicado, fuera del
 * planificador compartido de Spring (un solo hilo por defecto, el del outbox y los jobs de F9).
 *
 * <p><b>No es un bean de tipo {@code TaskScheduler}, y a proposito.</b> Spring Boot crea su
 * {@code taskScheduler} solo si no hay ningun bean {@code TaskScheduler} ni
 * {@code ScheduledExecutorService}, y el procesador de {@code @Scheduled} usa como default el unico
 * que encuentre: un {@code ThreadPoolTaskScheduler} publicado como bean para la biblioteca se
 * volveria el planificador de TODOS los modulos. Por eso el planificador vive adentro de esta
 * clase y las dos tareas se programan a mano.
 *
 * <p>El hilo es daemon y el apagado interrumpe: un libro a medio procesar queda PROCESANDO y lo
 * devuelve a la cola el barrido por latido. Un {@code Error} en una vuelta (por ejemplo
 * {@code OutOfMemoryError}) lo loguea el manejador del planificador y la tarea sigue programada.
 */
@Component
@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.worker.enabled:true}")
public class PlanificadorDeBiblioteca implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(PlanificadorDeBiblioteca.class);

	private final ProcesamientoWorker procesamiento;
	private final ReindexadoWorker reindexado;
	private final BibliotecaProperties.Worker ajustes;
	private ThreadPoolTaskScheduler hilo;

	public PlanificadorDeBiblioteca(ProcesamientoWorker procesamiento, ReindexadoWorker reindexado,
			BibliotecaProperties properties) {
		this.procesamiento = procesamiento;
		this.reindexado = reindexado;
		this.ajustes = properties.worker();
	}

	@Override
	public synchronized void start() {
		if (hilo != null) {
			return;
		}
		ThreadPoolTaskScheduler nuevo = new ThreadPoolTaskScheduler();
		nuevo.setPoolSize(1);
		nuevo.setThreadNamePrefix("biblioteca-worker-");
		nuevo.setDaemon(true);
		nuevo.setWaitForTasksToCompleteOnShutdown(false);
		nuevo.setErrorHandler(falla -> log.error("Una vuelta del worker de la biblioteca termino con {}",
				falla.getClass().getName()));
		nuevo.initialize();
		Instant primero = Instant.now().plusMillis(ajustes.initialDelayMs());
		nuevo.scheduleWithFixedDelay(procesamiento::tick, primero, Duration.ofMillis(ajustes.fixedDelayMs()));
		nuevo.scheduleWithFixedDelay(reindexado::tick, primero, Duration.ofMillis(ajustes.reindexFixedDelayMs()));
		hilo = nuevo;
	}

	@Override
	public synchronized void stop() {
		if (hilo != null) {
			hilo.shutdown();
			hilo = null;
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return hilo != null;
	}
}
