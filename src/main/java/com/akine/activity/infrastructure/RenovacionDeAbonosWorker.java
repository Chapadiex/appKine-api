package com.akine.activity.infrastructure;

import com.akine.activity.application.RenovacionDeAbonos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara la renovacion automatica de abonos cada
 * {@code akine.activity.abonos.renovacion.fixed-delay-ms} (una hora por defecto). El QUE vive en
 * {@link RenovacionDeAbonos}; esta clase solo decide CUANDO, como {@link VencimientoDePasesWorker}.
 *
 * <p>{@code anticipacion-dias} (3 por defecto) es cuanto antes del fin del periodo se vende el
 * siguiente: la deuda del periodo nuevo existe antes de que empiece, para poder cobrarla por
 * adelantado. Con 0 se renueva el ultimo dia del periodo.
 *
 * <p><b>Nunca propaga</b> y no esta expuesto por HTTP (plan 08.08: "jobs no expuestos
 * publicamente").
 */
@Component
@ConditionalOnProperty(
		name = "akine.activity.abonos.renovacion.enabled", havingValue = "true", matchIfMissing = true)
public class RenovacionDeAbonosWorker {

	private static final Logger log = LoggerFactory.getLogger(RenovacionDeAbonosWorker.class);

	static final int LOTE = 200;
	static final int LOTES_POR_TICK = 10;

	private final RenovacionDeAbonos renovacion;
	private final int anticipacionDias;

	public RenovacionDeAbonosWorker(
			RenovacionDeAbonos renovacion,
			@Value("${akine.activity.abonos.renovacion.anticipacion-dias:3}") int anticipacionDias) {
		if (anticipacionDias < 0) {
			throw new IllegalArgumentException("La anticipacion de la renovacion no puede ser negativa");
		}
		this.renovacion = renovacion;
		this.anticipacionDias = anticipacionDias;
	}

	@Scheduled(
			fixedDelayString = "${akine.activity.abonos.renovacion.fixed-delay-ms:3600000}",
			initialDelayString = "${akine.activity.abonos.renovacion.initial-delay-ms:180000}")
	public void tick() {
		try {
			for (int i = 0; i < LOTES_POR_TICK; i++) {
				if (renovacion.renovarLosQueTerminan(anticipacionDias, LOTE) < LOTE) {
					return;
				}
			}
		} catch (RuntimeException e) {
			log.error("El tick de renovacion de abonos fallo; se reintenta en el proximo ciclo", e);
		}
	}
}
