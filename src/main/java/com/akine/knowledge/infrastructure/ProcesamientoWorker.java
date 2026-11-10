package com.akine.knowledge.infrastructure;

import com.akine.knowledge.application.ProcesamientoDeLibroService;
import com.akine.knowledge.infrastructure.config.BibliotecaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Procesa UN libro PENDIENTE por vuelta. Como {@code OutboxWorker}: esta clase decide CUANDO y el
 * QUE vive en {@link ProcesamientoDeLibroService}, que los IT llaman a mano.
 *
 * <p>No usa {@code @Scheduled}: la dispara {@link PlanificadorDeBiblioteca} en un hilo propio del
 * modulo, para que un libro largo no frene al planificador compartido de Spring (outbox, jobs de
 * pases). Un libro por vuelta: entre libro y libro el hilo se libera y el reindexado entra.
 *
 * <p>Corre solo con la biblioteca habilitada y el worker encendido
 * ({@code akine.biblioteca.worker.enabled}, default true). Antes de procesar devuelve a la cola
 * los PROCESANDO cuyo latido vencio (un nodo que se cayo a mitad de un libro).
 *
 * <p><b>Nunca propaga</b> y nunca loguea el texto del libro: solo la clase del error.
 */
@Component
@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.worker.enabled:true}")
public class ProcesamientoWorker {

	private static final Logger log = LoggerFactory.getLogger(ProcesamientoWorker.class);

	private final ProcesamientoDeLibroService procesamiento;
	private final Duration lease;

	public ProcesamientoWorker(ProcesamientoDeLibroService procesamiento, BibliotecaProperties properties) {
		this.procesamiento = procesamiento;
		this.lease = Duration.ofMinutes(properties.worker().leaseMinutos());
	}

	public void tick() {
		try {
			procesamiento.devolverTrabados(Instant.now().minus(lease));
			procesamiento.procesarSiguiente(Instant.now());
		} catch (RuntimeException e) {
			log.error("El tick de la biblioteca fallo ({}); se reintenta en el proximo ciclo",
					e.getClass().getName());
		}
	}
}
