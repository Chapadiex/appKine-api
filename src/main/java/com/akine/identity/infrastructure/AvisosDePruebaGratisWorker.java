package com.akine.identity.infrastructure;

import com.akine.identity.application.AvisosDePruebaGratis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Pasa cada hora por las pruebas gratis que estan por terminar o terminaron (18/11/2026). Es
 * idempotente: cada aviso tiene su clave en el outbox, asi que repetir no duplica correos.
 *
 * <p><b>Nunca propaga</b>: una excepcion que escapa de un {@code @Scheduled} apaga la tarea.
 */
@Component
@ConditionalOnProperty(name = "akine.prueba-gratis.avisos.enabled", havingValue = "true", matchIfMissing = true)
class AvisosDePruebaGratisWorker {

	private static final Logger log = LoggerFactory.getLogger(AvisosDePruebaGratisWorker.class);

	private final AvisosDePruebaGratis avisos;
	private final Clock clock;

	AvisosDePruebaGratisWorker(AvisosDePruebaGratis avisos) {
		this.avisos = avisos;
		this.clock = Clock.systemUTC();
	}

	@Scheduled(
			fixedDelayString = "${akine.prueba-gratis.avisos.fixed-delay-ms:3600000}",
			initialDelayString = "${akine.prueba-gratis.avisos.initial-delay-ms:300000}")
	public void tick() {
		try {
			avisos.avisarVencimientos(clock.instant());
		} catch (RuntimeException e) {
			log.error("El tick de avisos de prueba gratis fallo; se reintenta en el proximo ciclo", e);
		}
	}
}
