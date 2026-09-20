package com.akine.billing.application;

import com.akine.billing.domain.PagoEgreso;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un pago tal como se lee.
 *
 * <p><b>Los anulados se muestran.</b> Ocultarlos haria que un egreso con un pago anulado pareciera
 * no haberse pagado nunca, y el historial de "se pago el 10 y se anulo el 12" es exactamente lo que
 * una auditoria busca.
 */
public record PagoEgresoView(
		long id,
		long egresoId,
		BigDecimal importe,
		String moneda,
		String medio,
		String referencia,
		LocalDate fechaNegocio,
		String estado,
		Instant pagadoEn,
		long pagadoPorCuentaId,
		Instant anuladoEn,
		String motivoAnulacion) {

	public static PagoEgresoView de(PagoEgreso pago) {
		return new PagoEgresoView(
				pago.getId(),
				pago.getEgresoId(),
				pago.getImporte(),
				pago.getMoneda(),
				pago.getMedio().name(),
				pago.getReferencia(),
				pago.getFechaNegocio(),
				pago.getEstado().name(),
				pago.getPagadoEn(),
				pago.getPagadoPorCuentaId(),
				pago.getAnuladoEn(),
				pago.getMotivoAnulacion());
	}
}
