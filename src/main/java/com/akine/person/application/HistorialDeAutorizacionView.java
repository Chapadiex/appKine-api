package com.akine.person.application;

import com.akine.person.domain.Autorizacion;

import java.time.LocalDate;
import java.util.List;

/**
 * Una pagina del historial de una autorizacion, con el vencimiento CALCULADO al lado (DP-23).
 *
 * <p>{@code vencida} y {@code vencidaDesde} no salen de ninguna fila: vencer es funcion del reloj
 * y no un acto, asi que no hay evento que lo registre. {@code vencidaDesde} es el dia siguiente al
 * ultimo dia de vigencia —la vigencia es inclusiva—.
 */
public record HistorialDeAutorizacionView(
		long autorizacionId,
		String estadoActual,
		boolean activa,
		boolean vencida,
		LocalDate vencidaDesde,
		List<EventoDeAutorizacionView> contenido,
		long total) {

	static HistorialDeAutorizacionView de(
			Autorizacion autorizacion,
			List<EventoDeAutorizacionView> contenido,
			long total,
			LocalDate fecha) {

		boolean vencida = autorizacion.vencidaEl(fecha);
		return new HistorialDeAutorizacionView(
				autorizacion.getId(),
				autorizacion.getEstado().name(),
				autorizacion.isActive(),
				vencida,
				vencida ? autorizacion.getVigenciaHasta().plusDays(1) : null,
				contenido,
				total);
	}
}
