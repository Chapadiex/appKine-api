package com.akine.contracting.api.dto;

import com.akine.contracting.application.PlanCoberturaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un plan de cobertura de un financiador (M15).
 *
 * <p><b>{@code estado} y {@code vigente} son dos cosas distintas y viajan las dos.</b> El estado
 * es el ciclo de vida administrativo; {@code vigente} dice si la fecha consultada cae dentro de la
 * ventana. Un plan ACTIVO con {@code vigente = false} es el caso borde de la etapa —"plan sin
 * nuevas altas pero con pacientes vigentes"— y colapsarlos en un solo campo dejaria a la pantalla
 * sin poder explicar por que ese plan no se ofrece.
 */
@Schema(description = "Plan de cobertura de un financiador")
public record PlanCoberturaResponse(

		@Schema(description = "Identificador del plan", example = "88")
		long id,

		@Schema(description = "Financiador al que pertenece. No cambia nunca", example = "31")
		long financiadorId,

		@Schema(description = "Clave estable dentro del financiador. No cambia", example = "210")
		String codigo,

		@Schema(description = "Nombre visible del plan", example = "Plan 210")
		String nombre,

		@Schema(description = "Descripcion administrativa")
		String descripcion,

		@Schema(description = "Primer dia en que se puede elegir", example = "2026-01-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que se puede elegir, INCLUSIVE. Null = sin fin")
		LocalDate vigenciaHasta,

		@Schema(
				description = "Si la fecha consultada cae dentro de la vigencia. DISTINTO de "
						+ "estado: un plan ACTIVO con la vigencia cerrada devuelve false")
		boolean vigente,

		@Schema(description = "Si las prestaciones exigen autorizacion previa del financiador")
		boolean requiereAutorizacion,

		@Schema(description = "Si la cobertura del paciente exige numero de credencial")
		boolean requiereCredencial,

		@Schema(description = "Copago de referencia. Null = sin copago declarado, distinto de cero",
				example = "1500.00")
		BigDecimal copago,

		@Schema(description = "ISO 4217 del copago. Null si y solo si copago es null",
				example = "ARS")
		String moneda,

		@Schema(
				description = "Ciclo de vida. Un plan INACTIVO se sigue leyendo con 200",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version) {

	public static PlanCoberturaResponse de(PlanCoberturaView view) {
		return new PlanCoberturaResponse(
				view.id(),
				view.financiadorId(),
				view.codigo(),
				view.nombre(),
				view.descripcion(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
				view.vigente(),
				view.requiereAutorizacion(),
				view.requiereCredencial(),
				view.copago(),
				view.moneda(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
