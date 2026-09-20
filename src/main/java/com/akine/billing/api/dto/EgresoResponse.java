package com.akine.billing.api.dto;

import com.akine.billing.application.EgresoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un egreso: lo que el centro <b>debe</b> a un beneficiario.
 *
 * <p><b>No es el pago ni el movimiento de caja.</b> Un egreso confirmado con
 * {@code saldoPendiente == importeTotal} es una deuda viva sobre la que no se movio un peso, y esa
 * es exactamente la situacion que M22 existe para poder representar.
 */
@Schema(
		name = "Egreso",
		description = "Compromiso de pago del centro. Nace en BORRADOR, se confirma —y ahi se "
				+ "congelan beneficiario, importe y comprobante— y se salda con uno o varios pagos.")
public record EgresoResponse(

		@Schema(example = "5501")
		long id,

		@Schema(example = "17")
		long consultorioId,

		@Schema(example = "HONORARIOS_PROFESIONALES")
		String categoria,

		@Schema(allowableValues = {"COLABORADOR", "EXTERNO"}, example = "COLABORADOR")
		String tipoBeneficiario,

		@Schema(description = "El vinculo, no la cuenta.", example = "412")
		Long beneficiarioMembershipId,

		@Schema(
				description = "**Congelado al crear.** Una liquidacion de septiembre se lee en marzo "
						+ "sin depender de que la membership siga existiendo.",
				example = "Lucia Fernandez")
		String beneficiarioNombre,

		@Schema(example = "30712345678")
		String beneficiarioDocumento,

		@Schema(example = "2026-09-01")
		LocalDate periodoDesde,

		@Schema(example = "2026-09-30")
		LocalDate periodoHasta,

		@Schema(example = "Honorarios de septiembre 2026")
		String concepto,

		@Schema(example = "185000.00")
		BigDecimal importeTotal,

		@Schema(
				description = "Lo que **todavia** se debe. `importeTotal - saldoPendiente` es lo ya "
						+ "pagado: son dos numeros distintos a proposito.",
				example = "85000.00")
		BigDecimal saldoPendiente,

		@Schema(example = "ARS")
		String moneda,

		@Schema(allowableValues = {"BORRADOR", "CONFIRMADO", "PAGADO", "ANULADO"}, example = "CONFIRMADO")
		String estado,

		@Schema(example = "FACTURA_C")
		String comprobanteTipo,

		@Schema(example = "0001-00000123")
		String comprobanteNumero,

		@Schema(example = "2026-10-03")
		LocalDate comprobanteFecha,

		Instant registradoEn,

		@Schema(example = "1204")
		long registradoPorCuentaId,

		Instant confirmadoEn,

		Instant anuladoEn,

		String motivoAnulacion,

		@Schema(
				description = "Control optimista del **borrador**. El saldo no lo mueve: se toca en "
						+ "fases distintas y nunca corren la misma carrera.",
				example = "2")
		long version,

		@Schema(description = "Vacio en los listados, completo en el detalle. **Incluye los anulados.**")
		List<PagoEgresoResponse> pagos) {

	public static EgresoResponse de(EgresoView vista) {
		return new EgresoResponse(
				vista.id(),
				vista.consultorioId(),
				vista.categoria(),
				vista.tipoBeneficiario(),
				vista.beneficiarioMembershipId(),
				vista.beneficiarioNombre(),
				vista.beneficiarioDocumento(),
				vista.periodoDesde(),
				vista.periodoHasta(),
				vista.concepto(),
				vista.importeTotal(),
				vista.saldoPendiente(),
				vista.moneda(),
				vista.estado(),
				vista.comprobanteTipo(),
				vista.comprobanteNumero(),
				vista.comprobanteFecha(),
				vista.registradoEn(),
				vista.registradoPorCuentaId(),
				vista.confirmadoEn(),
				vista.anuladoEn(),
				vista.motivoAnulacion(),
				vista.version(),
				vista.pagos().stream().map(PagoEgresoResponse::de).toList());
	}
}
