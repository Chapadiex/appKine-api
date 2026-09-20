package com.akine.billing.application;

import com.akine.billing.domain.MovimientoCaja;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un movimiento del ledger tal como se lee.
 *
 * @param importe            <b>siempre positivo</b>: el signo lo dice {@code tipo}
 * @param afectaArqueo       si mueve el saldo que se cuenta al arquear. Solo el efectivo
 * @param jornadaCajaId      {@code null} cuando la plata nunca toco el cajon y no habia caja abierta
 * @param movimientoOrigenId el movimiento que este compensa, cuando es una reversion (RF-M24-006)
 */
public record MovimientoCajaView(
		long id,
		Long jornadaCajaId,
		LocalDate fechaNegocio,
		String tipo,
		String medio,
		BigDecimal importe,
		String moneda,
		boolean afectaArqueo,
		String concepto,
		String motivo,
		String tipoOrigen,
		Long referenciaOrigen,
		Long movimientoOrigenId,
		Instant registradoEn,
		long registradoPorCuentaId) {

	public static MovimientoCajaView de(MovimientoCaja movimiento) {
		return new MovimientoCajaView(
				movimiento.getId(),
				movimiento.getJornadaCajaId(),
				movimiento.getFechaNegocio(),
				movimiento.getTipo().name(),
				movimiento.getMedio().name(),
				movimiento.getImporte(),
				movimiento.getMoneda(),
				movimiento.afectaArqueo(),
				movimiento.getConcepto(),
				movimiento.getMotivo(),
				movimiento.getTipoOrigen().name(),
				movimiento.getReferenciaOrigen(),
				movimiento.getMovimientoOrigenId(),
				movimiento.getRegistradoEn(),
				movimiento.getRegistradoPorCuentaId());
	}
}
