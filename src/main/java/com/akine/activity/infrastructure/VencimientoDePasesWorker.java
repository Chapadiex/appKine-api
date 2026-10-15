package com.akine.activity.infrastructure;

import com.akine.activity.application.VencimientoDePases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara el vencimiento de pases cada {@code akine.activity.pases.vencimiento.fixed-delay-ms} (una
 * hora por defecto). El QUE vive en {@link VencimientoDePases}; esta clase solo decide CUANDO, como
 * {@code OutboxWorker}, y por la misma razon: se prueba sin scheduler y sin esperar.
 *
 * <p>{@code fixedDelay}: un tick no arranca hasta que termino el anterior. Recorre lotes mientras
 * vengan llenos, con un tope por tick para no monopolizar el hilo del scheduler.
 *
 * <p><b>Nunca propaga</b>: una excepcion que escapa de un {@code @Scheduled} apaga la tarea en
 * silencio. No esta expuesto por HTTP (plan 08.08: "jobs no expuestos publicamente").
 */
@Component
@ConditionalOnProperty(
		name = "akine.activity.pases.vencimiento.enabled", havingValue = "true", matchIfMissing = true)
public class VencimientoDePasesWorker {

	private static final Logger log = LoggerFactory.getLogger(VencimientoDePasesWorker.class);

	static final int LOTE = 200;
	static final int LOTES_POR_TICK = 10;

	private final VencimientoDePases vencimiento;

	public VencimientoDePasesWorker(VencimientoDePases vencimiento) {
		this.vencimiento = vencimiento;
	}

	@Scheduled(
			fixedDelayString = "${akine.activity.pases.vencimiento.fixed-delay-ms:3600000}",
			initialDelayString = "${akine.activity.pases.vencimiento.initial-delay-ms:120000}")
	public void tick() {
		try {
			for (int i = 0; i < LOTES_POR_TICK; i++) {
				if (vencimiento.vencerLosVencidos(LOTE) < LOTE) {
					return;
				}
			}
		} catch (RuntimeException e) {
			log.error("El tick de vencimiento de pases fallo; se reintenta en el proximo ciclo", e);
		}
	}
}
