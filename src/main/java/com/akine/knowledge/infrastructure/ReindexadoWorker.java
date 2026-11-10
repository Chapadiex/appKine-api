package com.akine.knowledge.infrastructure;

import com.akine.knowledge.application.ReindexadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reindexa pedazos pendientes de vector cada {@code akine.biblioteca.worker.reindex-fixed-delay-ms}
 * (30 s por defecto). Mismo interruptor que {@link ProcesamientoWorker}. Cada tick recorre lotes
 * hasta que no queda nada o llega al tope, para no acaparar el planificador. Nunca propaga y
 * nunca loguea texto.
 */
@Component
@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.worker.enabled:true}")
public class ReindexadoWorker {

	private static final Logger log = LoggerFactory.getLogger(ReindexadoWorker.class);

	static final int LOTES_POR_TICK = 20;

	private final ReindexadoService reindexado;

	public ReindexadoWorker(ReindexadoService reindexado) {
		this.reindexado = reindexado;
	}

	@Scheduled(
			fixedDelayString = "${akine.biblioteca.worker.reindex-fixed-delay-ms:30000}",
			initialDelayString = "${akine.biblioteca.worker.initial-delay-ms:30000}")
	public void tick() {
		try {
			for (int i = 0; i < LOTES_POR_TICK; i++) {
				if (reindexado.reindexarLote() == 0) {
					break;
				}
			}
		} catch (RuntimeException e) {
			log.error("El reindexado de la biblioteca fallo ({}); se reintenta en el proximo ciclo",
					e.getClass().getName());
		}
	}
}
