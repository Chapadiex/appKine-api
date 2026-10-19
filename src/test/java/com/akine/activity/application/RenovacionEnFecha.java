package com.akine.activity.application;

import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoARenovar;

import java.time.Instant;

/**
 * Para los IT: renueva UN abono como si el job corriera en {@code ahora}. Las fechas de un periodo
 * anclado (DP-40, U-45) no se pueden esperar, y correr el job entero con un reloj fijo tocaria los
 * abonos de los demas tests.
 */
public final class RenovacionEnFecha {

	private RenovacionEnFecha() {
	}

	public static RenovacionDeAbonos.Resultado renovar(
			RenovacionDeAbonos renovacion, CandidatoARenovar candidato, int anticipacionDias, Instant ahora) {
		return renovacion.renovarUno(candidato, anticipacionDias, ahora);
	}
}
