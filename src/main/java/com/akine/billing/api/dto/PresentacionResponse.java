package com.akine.billing.api.dto;

import com.akine.billing.application.PresentacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un lote reclamado a un financiador.
 *
 * <p><b>Los importes viajan como decimal exacto, nunca como float.</b> Y los cuatro viajan juntos:
 * una pantalla que muestra solo el saldo no puede explicar de que esta hecho.
 */
@Schema(
		name = "Presentacion",
		description = "Lote reclamado a un financiador (M21). **No es un cobro y no es la caja**: "
				+ "prestado, presentado, facturado y cobrado son cuatro estados distintos.")
public record PresentacionResponse(

		@Schema(example = "1204")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(example = "31")
		long financiadorId,

		@Schema(description = "Nombre del financiador, resuelto al leer", example = "OSDE")
		String financiadorNombre,

		@Schema(
				description = "Correlativo del lote por sede y financiador. **Ausente mientras es "
						+ "un borrador**: se asigna al confirmar.",
				example = "48")
		Integer numero,

		@Schema(example = "2026-08-01")
		LocalDate periodoDesde,

		@Schema(description = "Ultimo dia INCLUSIVE", example = "2026-08-31")
		LocalDate periodoHasta,

		@Schema(example = "ARS")
		String moneda,

		@Schema(
				description = "`BORRADOR` se arma; `PRESENTADA` se envio; `FACTURADA` ademas tiene "
						+ "comprobante; `CONCILIADA` quedo explicada por completo; `ANULADA` es un "
						+ "borrador descartado.",
				allowableValues = {"BORRADOR", "PRESENTADA", "FACTURADA", "CONCILIADA", "ANULADA"},
				example = "PRESENTADA")
		String estado,

		@Schema(description = "Suma de las prestaciones. Se congela al confirmar.", example = "100000.00")
		BigDecimal totalPresentado,

		@Schema(description = "Lo que el financiador rechazo", example = "15000.00")
		BigDecimal totalDebitado,

		@Schema(description = "Lo que efectivamente pago", example = "85000.00")
		BigDecimal totalCobrado,

		@Schema(
				description = "`totalPresentado - totalDebitado - totalCobrado`. **Conciliar exige "
						+ "que sea cero**: no hay cierre con diferencia.",
				example = "0.00")
		BigDecimal saldo,

		@Schema(description = "Comprobante externo del centro. Ausente si no se registro.", example = "0001-00004521")
		String facturaNumero,

		@Schema(example = "2026-09-05")
		LocalDate facturaFecha,

		@Schema(description = "Ausente mientras es un borrador")
		Instant confirmadaEn,

		@Schema(description = "Ausente hasta que el lote cierra")
		Instant conciliadaEn,

		@Schema(description = "Ausente si el borrador no se descarto")
		Instant anuladaEn,

		@Schema(description = "Obligatorio al anular")
		String motivoAnulacion,

		@Schema(example = "2026-09-01T10:00:00Z")
		Instant creadaEn,

		@Schema(description = "Version para el control optimista", example = "0")
		long version,

		@Schema(description = "Las prestaciones del lote. **Ausente en los listados.**")
		List<PresentacionItemResponse> items) {

	public static PresentacionResponse de(PresentacionView vista) {
		return new PresentacionResponse(
				vista.id(), vista.consultorioId(), vista.financiadorId(), vista.financiadorNombre(),
				vista.numero(), vista.periodoDesde(), vista.periodoHasta(), vista.moneda(),
				vista.estado().name(), vista.totalPresentado(), vista.totalDebitado(),
				vista.totalCobrado(), vista.saldo(), vista.facturaNumero(), vista.facturaFecha(),
				vista.confirmadaEn(), vista.conciliadaEn(), vista.anuladaEn(),
				vista.motivoAnulacion(), vista.creadaEn(), vista.version(),
				vista.items() == null
						? null
						: vista.items().stream().map(PresentacionItemResponse::de).toList());
	}
}
