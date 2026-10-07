package com.akine.person.application;

import com.akine.person.domain.AutorizacionAlerta;

import java.time.Instant;

/**
 * Una alerta sobre una autorizacion, tal como sale del servicio (DP-13, RN-M17-003, AKINE C-4).
 */
public record AlertaDeAutorizacionView(
		long id,
		long autorizacionId,
		String tipo,
		long movimientoId,
		long sesionId,
		long obligacionId,
		String motivoOrigen,
		Instant generadaEn,
		Long generadaPor,
		boolean pendiente,
		String resolucion,
		Instant resueltaEn,
		Long resueltaPor) {

	public static AlertaDeAutorizacionView de(AutorizacionAlerta alerta) {
		return new AlertaDeAutorizacionView(
				alerta.getId(),
				alerta.getAutorizacionId(),
				alerta.getTipo().name(),
				alerta.getMovimientoId(),
				alerta.getSesionId(),
				alerta.getObligacionId(),
				alerta.getMotivoOrigen(),
				alerta.getGeneradaEn(),
				alerta.getGeneradaPor(),
				alerta.pendiente(),
				alerta.getResolucion() == null ? null : alerta.getResolucion().name(),
				alerta.getResueltaEn(),
				alerta.getResueltaPor());
	}
}
