package com.akine.billing.infrastructure;

import com.akine.billing.application.ImputacionDePrepago;
import com.akine.billing.application.ImputacionDePrepago.CierreConTurno;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.encounter.spi.SesionCerrada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cuando cierra una sesion que salio de un turno, imputa el prepago que la recepcion de ese turno
 * tomo (AKINE E-6). Ver {@link ImputacionDePrepago}.
 *
 * <p><b>No hace nada dentro del cierre.</b> Se anota para despues del commit: asi la imputacion
 * ve la deuda que el cierre devengo —ya commiteada— y, sobre todo, <b>nunca hace fallar el
 * cierre</b>. Es la diferencia con {@link ObligacionDevengador}, que si corre adentro: la deuda
 * tiene que nacer con la prestacion (07.01); el cobro no (DP-06).
 *
 * <p><b>Sin asistencia no hay nada que imputar</b>: no se devenga deuda, y el anticipo queda a
 * favor para reintegrarlo o imputarlo despues (F-3). Ningun no-show se cobra solo.
 */
@Component
public class PrepagoAlCierreDeSesion implements CierreDeSesionObserver {

	private static final Logger log = LoggerFactory.getLogger(PrepagoAlCierreDeSesion.class);

	private final ImputacionDePrepago imputacion;

	public PrepagoAlCierreDeSesion(ImputacionDePrepago imputacion) {
		this.imputacion = imputacion;
	}

	@Override
	public void alCerrar(SesionCerrada cierre) {
		if (!cierre.asistio() || cierre.turnoId() == null) {
			return;
		}
		CierreConTurno hecho = new CierreConTurno(
				cierre.organizationId(), cierre.consultorioId(), cierre.sesionId(), cierre.turnoId(),
				cierre.personaId(), cierre.cerradaPorCuentaId());

		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					imputar(hecho);
				}
			});
		} else {
			imputar(hecho);
		}
	}

	/** Un fallo se loguea y el anticipo queda a favor: el cierre ya commiteo y no se toca. */
	void imputar(CierreConTurno hecho) {
		try {
			imputacion.imputarAlCierre(hecho);
		} catch (RuntimeException fallo) {
			log.warn("No se pudo imputar el prepago al cierre; queda como saldo a favor para "
							+ "imputarlo a mano. sesionId={} turnoId={} causa={}",
					hecho.sesionId(), hecho.turnoId(), fallo.toString());
		}
	}
}
