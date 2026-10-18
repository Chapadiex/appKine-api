package com.akine.activity.infrastructure;

import com.akine.activity.application.AvisosDePases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara los avisos de pases y abonos (RF-M26-008) cada
 * {@code akine.activity.pases.avisos.fixed-delay-ms} (una hora por defecto). El QUE vive en
 * {@link AvisosDePases}.
 *
 * <p>Los dos umbrales son la parte "configurable" de RF-M26-008, por despliegue:
 * {@code dias-antes-del-vencimiento} (3 por defecto; con 0 se avisa el ultimo dia) y
 * {@code saldo-bajo} (1 por defecto: queda un credito; con 0 no se avisa saldo bajo). Un umbral por
 * organizacion o por producto necesita una pantalla de configuracion que ningun RF describe.
 *
 * <p><b>Nunca propaga</b> y no esta expuesto por HTTP.
 */
@Component
@ConditionalOnProperty(
		name = "akine.activity.pases.avisos.enabled", havingValue = "true", matchIfMissing = true)
public class AvisosDePasesWorker {

	private static final Logger log = LoggerFactory.getLogger(AvisosDePasesWorker.class);

	static final int LOTE = 200;

	private final AvisosDePases avisos;
	private final ObservacionDeJobs observacion;
	private final int diasAntes;
	private final int saldoBajo;

	public AvisosDePasesWorker(
			AvisosDePases avisos,
			ObservacionDeJobs observacion,
			@Value("${akine.activity.pases.avisos.dias-antes-del-vencimiento:3}") int diasAntes,
			@Value("${akine.activity.pases.avisos.saldo-bajo:1}") int saldoBajo) {
		if (diasAntes < 0 || saldoBajo < 0) {
			throw new IllegalArgumentException("Los umbrales de aviso de pases no pueden ser negativos");
		}
		this.avisos = avisos;
		this.observacion = observacion;
		this.diasAntes = diasAntes;
		this.saldoBajo = saldoBajo;
	}

	@Scheduled(
			fixedDelayString = "${akine.activity.pases.avisos.fixed-delay-ms:3600000}",
			initialDelayString = "${akine.activity.pases.avisos.initial-delay-ms:240000}")
	public void tick() {
		try {
			observacion.observar(ObservacionDeJobs.AVISO_DE_VENCIMIENTO,
					() -> avisos.avisarVencimientos(diasAntes, LOTE));
		} catch (RuntimeException e) {
			log.error("El tick de avisos de vencimiento fallo; se reintenta en el proximo ciclo", e);
		}
		try {
			observacion.observar(ObservacionDeJobs.AVISO_DE_SALDO_BAJO,
					() -> avisos.avisarSaldosBajos(saldoBajo, LOTE));
		} catch (RuntimeException e) {
			log.error("El tick de avisos de saldo bajo fallo; se reintenta en el proximo ciclo", e);
		}
	}
}
