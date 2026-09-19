package com.akine.clinical.application;

import com.akine.clinical.domain.CasoEvento;

import java.time.Instant;

/**
 * Una transicion del caso, tal como sale de la aplicacion.
 *
 * <p><b>{@code detalle} nunca lleva contenido clinico:</b> dice que se edito el objetivo, no cual
 * era. Este historial lo lee tambien quien abrio la ficha sin abrir el caso, y el diagnostico y el
 * objetivo se leen del caso, con su propio acceso auditado.
 *
 * @param estadoAnterior {@code null} solo en la APERTURA: antes no habia estado
 * @param motivo         presente en CIERRE y REAPERTURA, que lo exigen; {@code null} en el resto
 */
public record CasoEventoView(
		long id,
		String tipo,
		String estadoAnterior,
		String estadoNuevo,
		String motivo,
		String detalle,
		Instant ocurrioEn,
		long actorCuentaId) {

	public static CasoEventoView de(CasoEvento evento) {
		return new CasoEventoView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior() == null ? null : evento.getEstadoAnterior().name(),
				evento.getEstadoNuevo().name(),
				evento.getMotivo(),
				evento.getDetalle(),
				evento.getOcurrioEn(),
				evento.getActorCuentaId());
	}
}
