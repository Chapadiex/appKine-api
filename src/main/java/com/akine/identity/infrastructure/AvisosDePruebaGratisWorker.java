package com.akine.identity.infrastructure;

import com.akine.identity.application.AvisosDeCobro;
import com.akine.identity.application.AvisosDePruebaGratis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Pasa cada hora por las pruebas gratis (18/11/2026) y los periodos pagados (19/11/2026) que estan
 * por vencer o vencieron. Es idempotente: cada aviso tiene su clave en el outbox, asi que repetir no
 * duplica correos.
 *
 * <p><b>Nunca propaga</b>: una excepcion que escapa de un {@code @Scheduled} apaga la tarea. Cada
 * tipo de aviso falla por separado: una prueba rota no deja sin avisos de cobro.
 */
@Component
@ConditionalOnProperty(name = "akine.prueba-gratis.avisos.enabled", havingValue = "true", matchIfMissing = true)
class AvisosDePruebaGratisWorker {

	private static final Logger log = LoggerFactory.getLogger(AvisosDePruebaGratisWorker.class);

	private final AvisosDePruebaGratis avisos;
	private final AvisosDeCobro cobros;
	private final Clock clock;

	AvisosDePruebaGratisWorker(AvisosDePruebaGratis avisos, AvisosDeCobro cobros) {
		this.avisos = avisos;
		this.cobros = cobros;
		this.clock = Clock.systemUTC();
	}

	@Scheduled(
			fixedDelayString = "${akine.prueba-gratis.avisos.fixed-delay-ms:3600000}",
			initialDelayString = "${akine.prueba-gratis.avisos.initial-delay-ms:300000}")
	public void tick() {
		Instant ahora = clock.instant();
		try {
			avisos.avisarVencimientos(ahora);
		} catch (RuntimeException e) {
			log.error("El tick de avisos de prueba gratis fallo; se reintenta en el proximo ciclo", e);
		}
		try {
			cobros.avisarVencimientos(ahora);
		} catch (RuntimeException e) {
			log.error("El tick de avisos de cobro fallo; se reintenta en el proximo ciclo", e);
		}
	}
}
