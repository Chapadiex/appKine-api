package com.akine.person.application;

import com.akine.person.domain.AutorizacionMovimiento;

import java.time.Instant;

/**
 * Un movimiento del ledger tal como sale del backend (RF-M17-004, RF-M17-005).
 *
 * <p>{@code cantidad} es <b>siempre positiva</b> y {@code efectoSobreElSaldo} trae el signo ya
 * resuelto. Viajan los dos a proposito: la pantalla que muestra la linea de tiempo quiere el
 * numero tal como se cargo, y la que suma quiere el signo sin tener que conocer la semantica de
 * cada tipo.
 *
 * <p>{@code tipoOrigen} + {@code referenciaOrigen} dicen QUE produjo el hecho. Sin ellos el ledger
 * seria una lista de numeros sin causa, que es exactamente lo que tener solo la columna
 * {@code cantidad_consumida} ya era.
 */
public record MovimientoView(
		long id,
		long autorizacionId,
		long personaId,
		Long consultorioId,
		String tipo,
		int cantidad,
		int efectoSobreElSaldo,
		String tipoOrigen,
		long referenciaOrigen,
		String motivo,
		Long movimientoOrigenId,
		Instant ocurrioEn,
		Long actorCuentaId) {

	public static MovimientoView de(AutorizacionMovimiento movimiento) {
		return new MovimientoView(
				movimiento.getId(),
				movimiento.getAutorizacionId(),
				movimiento.getPersonaId(),
				movimiento.getConsultorioId(),
				movimiento.getTipo().name(),
				movimiento.getCantidad(),
				movimiento.efectoSobreElSaldo(),
				movimiento.getTipoOrigen().name(),
				movimiento.getReferenciaOrigen(),
				movimiento.getMotivo(),
				movimiento.getMovimientoOrigenId(),
				movimiento.getOcurrioEn(),
				movimiento.getActorCuentaId());
	}
}
