package com.akine.knowledge.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.time.Duration;

/**
 * Al arrancar con la consulta prendida, pide a Ollama que cargue el modelo (diseño consulta v2,
 * §4.9): asi la primera consulta no se come el minuto o los tres que tarda en subirlo a la placa.
 * Corre en segundo plano y no frena el arranque; si Ollama no responde, solo un {@code warn}.
 */
public class PrecargaDelModelo implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger log = LoggerFactory.getLogger(PrecargaDelModelo.class);

	/** Cargar el modelo desde el disco puede llevar minutos en una maquina fria. */
	static final Duration PLAZO = Duration.ofMinutes(5);

	private final OllamaModeloDeLenguaje modelo;

	public PrecargaDelModelo(OllamaModeloDeLenguaje modelo) {
		this.modelo = modelo;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		long inicio = System.nanoTime();
		try {
			modelo.precargar(PLAZO).whenComplete((status, error) -> {
				long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
				if (error != null) {
					log.warn("Precarga del modelo de la consulta fallida: {}", causa(error));
				} else if (status != 200) {
					log.warn("Precarga del modelo de la consulta fallida: status={}", status);
				} else {
					log.info("Modelo de la consulta precargado: ms={}", ms);
				}
			});
		} catch (RuntimeException e) {
			log.warn("Precarga del modelo de la consulta fallida: {}", e.getClass().getSimpleName());
		}
	}

	private static String causa(Throwable error) {
		Throwable t = error.getCause() != null ? error.getCause() : error;
		return t.getClass().getSimpleName();
	}
}
