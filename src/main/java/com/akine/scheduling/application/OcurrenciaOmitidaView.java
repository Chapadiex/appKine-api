package com.akine.scheduling.application;

import com.akine.scheduling.domain.OcurrenciaOmitida;

import java.time.Instant;
import java.time.LocalDate;

/** Una ocurrencia que el alta de la serie no reservo, con su motivo (U-22). */
public record OcurrenciaOmitidaView(Instant inicio, LocalDate fecha, String motivo) {

	static OcurrenciaOmitidaView de(OcurrenciaOmitida omitida) {
		return new OcurrenciaOmitidaView(
				omitida.getOcurrenciaInicio(), omitida.getFecha(), omitida.getMotivo().name());
	}
}
