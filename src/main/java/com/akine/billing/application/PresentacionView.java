package com.akine.billing.application;

import com.akine.billing.domain.EstadoPresentacion;
import com.akine.billing.domain.Presentacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que sale de {@code application} sobre un lote. Las entities nunca cruzan el borde del service.
 *
 * <p>Los cuatro importes viajan juntos <b>a proposito</b>: una pantalla que muestra solo el saldo no
 * puede explicar de que esta hecho, y "¿por que me figura 85.000 si presente 100.000?" es la unica
 * pregunta que el administrativo hace sobre esta tabla.
 *
 * @param items {@code null} en los listados, poblado en el detalle. No se arma la lista completa
 *              para una bandeja de cien lotes
 */
public record PresentacionView(
		long id,
		long consultorioId,
		long financiadorId,
		String financiadorNombre,
		Integer numero,
		LocalDate periodoDesde,
		LocalDate periodoHasta,
		String moneda,
		EstadoPresentacion estado,
		BigDecimal totalPresentado,
		BigDecimal totalDebitado,
		BigDecimal totalCobrado,
		BigDecimal saldo,
		String facturaNumero,
		LocalDate facturaFecha,
		Instant confirmadaEn,
		Instant conciliadaEn,
		Instant anuladaEn,
		String motivoAnulacion,
		Instant creadaEn,
		long version,
		List<PresentacionItemView> items) {

	public static PresentacionView de(Presentacion presentacion, String financiadorNombre) {
		return de(presentacion, financiadorNombre, null);
	}

	public static PresentacionView de(
			Presentacion presentacion, String financiadorNombre, List<PresentacionItemView> items) {

		return new PresentacionView(
				presentacion.getId(),
				presentacion.getConsultorioId(),
				presentacion.getFinanciadorId(),
				financiadorNombre,
				presentacion.getNumero(),
				presentacion.getPeriodoDesde(),
				presentacion.getPeriodoHasta(),
				presentacion.getMoneda(),
				presentacion.getEstado(),
				presentacion.getTotalPresentado(),
				presentacion.getTotalDebitado(),
				presentacion.getTotalCobrado(),
				presentacion.getSaldo(),
				presentacion.getFacturaNumero(),
				presentacion.getFacturaFecha(),
				presentacion.getConfirmadaEn(),
				presentacion.getConciliadaEn(),
				presentacion.getAnuladaEn(),
				presentacion.getMotivoAnulacion(),
				presentacion.getCreadaEn(),
				presentacion.getVersion(),
				items);
	}
}
