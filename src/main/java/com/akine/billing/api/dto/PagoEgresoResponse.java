package com.akine.billing.api.dto;

import com.akine.billing.application.PagoEgresoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un pago de egreso.
 *
 * <p><b>Los anulados se devuelven.</b> Ocultarlos haria que un egreso con un pago anulado pareciera
 * no haberse pagado nunca, y el historial de "se pago el 10 y se anulo el 12" es lo que una
 * auditoria necesita ver.
 */
@Schema(
		name = "PagoDeEgreso",
		description = "El acto de saldar un egreso. Anular no lo borra: lo deja en `ANULADO` con su "
				+ "motivo, y devuelve la plata al cajon con una reversion trazable.")
public record PagoEgresoResponse(

		@Schema(example = "7701")
		long id,

		@Schema(example = "5501")
		long egresoId,

		@Schema(example = "100000.00")
		BigDecimal importe,

		@Schema(example = "ARS")
		String moneda,

		@Schema(allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"})
		String medio,

		@Schema(description = "Numero de transferencia o de recibo.", example = "TRF-99213847")
		String referencia,

		@Schema(
				description = "Dia operativo de la sede, con su zona IANA — no la del servidor.",
				example = "2026-10-05")
		LocalDate fechaNegocio,

		@Schema(allowableValues = {"CONFIRMADO", "ANULADO"}, example = "CONFIRMADO")
		String estado,

		Instant pagadoEn,

		@Schema(example = "1204")
		long pagadoPorCuentaId,

		Instant anuladoEn,

		@Schema(example = "Se pago al profesional equivocado")
		String motivoAnulacion) {

	public static PagoEgresoResponse de(PagoEgresoView vista) {
		return new PagoEgresoResponse(
				vista.id(),
				vista.egresoId(),
				vista.importe(),
				vista.moneda(),
				vista.medio(),
				vista.referencia(),
				vista.fechaNegocio(),
				vista.estado(),
				vista.pagadoEn(),
				vista.pagadoPorCuentaId(),
				vista.anuladoEn(),
				vista.motivoAnulacion());
	}
}
