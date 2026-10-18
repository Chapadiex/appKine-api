package com.akine.activity.application;

import com.akine.activity.domain.MovimientoPase;

import java.time.Instant;

/** Un movimiento del ledger de creditos (RN-M29-003). */
public record MovimientoPaseView(
		long id,
		String tipo,
		int cantidad,
		int saldoResultante,
		String origenTipo,
		long origenId,
		Long asistenciaId,
		Long actorCuentaId,
		String motivo,
		Instant ocurridoEn) {

	public static MovimientoPaseView de(MovimientoPase m) {
		return new MovimientoPaseView(
				m.getId(), m.getTipo().name(), m.getCantidad(), m.getSaldoResultante(),
				m.getOrigenTipo().name(), m.getOrigenId(), m.getAsistenciaId(),
				m.getActorCuentaId(), m.getMotivo(), m.getOcurridoEn());
	}
}
