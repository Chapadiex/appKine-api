package com.akine.billing.api.dto;

import com.akine.billing.application.PresentacionItemView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una prestacion dentro del lote.
 *
 * <p><b>PHI minima:</b> viaja el {@code personaId} y el concepto congelado, y <b>no</b> el nombre
 * del paciente ni su documento. El lote que se le manda al financiador si los lleva; este listado
 * no los necesita, y exponerlos seria filtrar el padron a cualquiera que pueda ver una bandeja.
 */
@Schema(
		name = "PresentacionItem",
		description = "Deuda de financiador dentro de un lote. Incluirla **no le mueve el saldo**: "
				+ "la obligacion se salda al conciliar.")
public record PresentacionItemResponse(

		@Schema(example = "55010")
		long id,

		@Schema(example = "9001")
		long obligacionId,

		@Schema(
				description = "`INCLUIDO` y `ACEPTADO` mantienen tomada la obligacion; `DEBITADO` "
						+ "y `ANULADO` la liberan para otro lote.",
				allowableValues = {"INCLUIDO", "ACEPTADO", "DEBITADO", "ANULADO"},
				example = "INCLUIDO")
		String estado,

		@Schema(description = "Lo que se reclama. Congelado al incluir.", example = "8500.00")
		BigDecimal importePresentado,

		@Schema(description = "Lo que el financiador rechazo", example = "0.00")
		BigDecimal importeDebitado,

		@Schema(description = "Obligatorio al debitar", example = "Falta autorizacion previa")
		String motivoDebito,

		@Schema(description = "Ausente si no se debito")
		Instant debitadoEn,

		@Schema(example = "128")
		long personaId,

		@Schema(description = "Referencia de trazabilidad congelada. La sesion no se toca.", example = "501")
		long sesionId,

		@Schema(example = "2026-08-14")
		LocalDate fechaPrestacion,

		@Schema(description = "Que se presto, al momento de devengar", example = "Sesion 8")
		String concepto,

		@Schema(example = "2026-09-01T10:00:00Z")
		Instant incluidoEn,

		@Schema(description = "Version para el control optimista", example = "0")
		long version) {

	public static PresentacionItemResponse de(PresentacionItemView vista) {
		return new PresentacionItemResponse(
				vista.id(), vista.obligacionId(), vista.estado().name(), vista.importePresentado(),
				vista.importeDebitado(), vista.motivoDebito(), vista.debitadoEn(),
				vista.personaId(), vista.sesionId(), vista.fechaPrestacion(), vista.concepto(),
				vista.incluidoEn(), vista.version());
	}
}
