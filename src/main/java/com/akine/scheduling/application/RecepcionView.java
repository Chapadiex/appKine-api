package com.akine.scheduling.application;

import com.akine.scheduling.domain.Recepcion;

import java.time.Instant;

/**
 * La recepcion de un turno tal como sale del servicio (M13, AKINE E-4).
 *
 * <p>PHI minima: nada clinico. La observacion es administrativa —"falta la orden medica"— y es lo
 * que la recepcion necesita para decirle algo al paciente.
 *
 * @param prepago el estado del prepago (AKINE E-6), calculado al leer. Nunca {@code null}
 */
public record RecepcionView(
		long id,
		long turnoId,
		String estado,
		Instant llegadaEn,
		long llegadaPorCuentaId,
		String modalidad,
		Long practicaId,
		Long coberturaId,
		Long convenioId,
		String observacion,
		String motivoParticular,
		Instant validadaEn,
		Instant enEsperaDesde,
		Instant llamadaEn,
		Instant cerradaEn,
		String motivoCierre,
		long version,
		PrepagoView prepago) {

	public static RecepcionView de(Recepcion recepcion, PrepagoView prepago) {
		return new RecepcionView(
				recepcion.getId(),
				recepcion.getTurnoId(),
				recepcion.getEstado().name(),
				recepcion.getLlegadaEn(),
				recepcion.getLlegadaPorCuentaId(),
				recepcion.getModalidad() == null ? null : recepcion.getModalidad().name(),
				recepcion.getPracticaId(),
				recepcion.getCoberturaId(),
				recepcion.getConvenioId(),
				recepcion.getObservacion(),
				recepcion.getMotivoParticular(),
				recepcion.getValidadaEn(),
				recepcion.getEnEsperaDesde(),
				recepcion.getLlamadaEn(),
				recepcion.getCerradaEn(),
				recepcion.getMotivoCierre(),
				recepcion.getVersion(),
				prepago);
	}
}
