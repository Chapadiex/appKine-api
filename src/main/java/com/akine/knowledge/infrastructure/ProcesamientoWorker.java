package com.akine.knowledge.infrastructure;

import com.akine.knowledge.application.ProcesamientoDeLibroService;
import com.akine.knowledge.infrastructure.config.BibliotecaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Procesa los libros PENDIENTE de la biblioteca cada {@code akine.biblioteca.worker.fixed-delay-ms}
 * (5 s por defecto). Como {@code OutboxWorker}: esta clase decide CUANDO y el QUE vive en
 * {@link ProcesamientoDeLibroService}, que los IT llaman a mano.
 *
 * <p>Corre solo con la biblioteca habilitada y el worker encendido
 * ({@code akine.biblioteca.worker.enabled}, default true). {@code fixedDelay}: un tick no arranca
 * hasta que termino el anterior. Antes de procesar devuelve a la cola los PROCESANDO cuyo lease
 * vencio (un nodo que se cayo a mitad de un libro).
 *
 * <p><b>Nunca propaga</b> y nunca loguea el texto del libro: solo la clase del error.
 */
@Component
@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.worker.enabled:true}")
public class ProcesamientoWorker {

	private static final Logger log = LoggerFactory.getLogger(ProcesamientoWorker.class);

	static final int LIBROS_POR_TICK = 5;

	private final ProcesamientoDeLibroService procesamiento;
	private final Duration lease;

	public ProcesamientoWorker(ProcesamientoDeLibroService procesamiento, BibliotecaProperties properties) {
		this.procesamiento = procesamiento;
		this.lease = Duration.ofMinutes(properties.worker().leaseMinutos());
	}

	@Scheduled(
			fixedDelayString = "${akine.biblioteca.worker.fixed-delay-ms:5000}",
			initialDelayString = "${akine.biblioteca.worker.initial-delay-ms:30000}")
	public void tick() {
		try {
			procesamiento.devolverTrabados(Instant.now().minus(lease));
			for (int i = 0; i < LIBROS_POR_TICK && procesamiento.procesarSiguiente(Instant.now()); i++) {
				// un libro por vuelta
			}
		} catch (RuntimeException e) {
			log.error("El tick de la biblioteca fallo ({}); se reintenta en el proximo ciclo",
					e.getClass().getName());
		}
	}
}
