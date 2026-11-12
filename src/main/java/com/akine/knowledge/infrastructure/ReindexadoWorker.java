package com.akine.knowledge.infrastructure;

import com.akine.knowledge.application.ReindexadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Reindexa pedazos pendientes de vector. Lo dispara {@link PlanificadorDeBiblioteca} cada
 * {@code akine.biblioteca.worker.reindex-fixed-delay-ms} (30 s por defecto) en el hilo propio de
 * la biblioteca. Cada tick recorre hasta {@link #LOTES_POR_TICK} lotes y avanza la version de
 * cada organizacion tocada una sola vez. Nunca propaga y nunca loguea texto.
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

	public void tick() {
		try {
			reindexado.reindexar(LOTES_POR_TICK);
		} catch (RuntimeException e) {
			log.error("El reindexado de la biblioteca fallo ({}); se reintenta en el proximo ciclo",
					e.getClass().getName());
		}
	}
}
