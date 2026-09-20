package com.akine.billing.application;

import com.akine.billing.domain.Egreso;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un egreso tal como se lee.
 *
 * @param saldoPendiente lo que <b>todavia se debe</b>. {@code importeTotal - saldoPendiente} es lo
 *                       ya pagado, y son dos numeros distintos a proposito: una liquidacion
 *                       confirmada y no pagada tiene saldo igual al total
 * @param pagos          vacio en los listados y completo en el detalle. Incluye los anulados
 */
public record EgresoView(
		long id,
		long consultorioId,
		String categoria,
		String tipoBeneficiario,
		Long beneficiarioMembershipId,
		String beneficiarioNombre,
		String beneficiarioDocumento,
		LocalDate periodoDesde,
		LocalDate periodoHasta,
		String concepto,
		BigDecimal importeTotal,
		BigDecimal saldoPendiente,
		String moneda,
		String estado,
		String comprobanteTipo,
		String comprobanteNumero,
		LocalDate comprobanteFecha,
		Instant registradoEn,
		long registradoPorCuentaId,
		Instant confirmadoEn,
		Instant anuladoEn,
		String motivoAnulacion,
		long version,
		List<PagoEgresoView> pagos) {

	/** Vista de listado: sin los pagos. Traerlos por cada fila es el N+1 del primer listado. */
	public static EgresoView de(Egreso egreso) {
		return de(egreso, List.of());
	}

	public static EgresoView de(Egreso egreso, List<PagoEgresoView> pagos) {
		return new EgresoView(
				egreso.getId(),
				egreso.getConsultorioId(),
				egreso.getCategoria().name(),
				egreso.getTipoBeneficiario().name(),
				egreso.getBeneficiarioMembershipId(),
				egreso.getBeneficiarioNombre(),
				egreso.getBeneficiarioDocumento(),
				egreso.getPeriodoDesde(),
				egreso.getPeriodoHasta(),
				egreso.getConcepto(),
				egreso.getImporteTotal(),
				egreso.getSaldoPendiente(),
				egreso.getMoneda(),
				egreso.getEstado().name(),
				egreso.getComprobanteTipo(),
				egreso.getComprobanteNumero(),
				egreso.getComprobanteFecha(),
				egreso.getRegistradoEn(),
				egreso.getRegistradoPorCuentaId(),
				egreso.getConfirmadoEn(),
				egreso.getAnuladoEn(),
				egreso.getMotivoAnulacion(),
				egreso.getVersion(),
				pagos);
	}
}
