package com.akine.contracting.api.dto;

import com.akine.contracting.application.PlanCatalogoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "Plan de un financiador del catalogo global de la plataforma (DP-43)")
public record PlanCatalogoResponse(

		@Schema(description = "Identificador del plan en el catalogo", example = "12")
		long id,

		@Schema(description = "Financiador del catalogo al que pertenece", example = "7")
		long financiadorCatalogoId,

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

		@Schema(description = "Si las prestaciones exigen autorizacion previa del financiador")
		boolean requiereAutorizacion,

		@Schema(description = "Si la cobertura del paciente exige numero de credencial")
		boolean requiereCredencial,

		@Schema(description = "Copago de referencia. Se copia como dato al adoptar",
				example = "1500.00")
		BigDecimal copago,

		@Schema(description = "ISO 4217 del copago. Null si y solo si copago es null",
				example = "ARS")
		String moneda,

		@Schema(description = "Ciclo de vida", example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version) {

	public static PlanCatalogoResponse de(PlanCatalogoView view) {
		return new PlanCatalogoResponse(
				view.id(),
				view.financiadorCatalogoId(),
				view.codigo(),
				view.nombre(),
				view.descripcion(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
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
