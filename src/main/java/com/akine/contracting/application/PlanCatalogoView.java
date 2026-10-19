package com.akine.contracting.application;

import com.akine.contracting.domain.PlanCatalogo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un plan del catalogo global de financiadores (DP-43), en la forma que {@code api} publica. */
public record PlanCatalogoView(
		long id,
		long financiadorCatalogoId,
		String codigo,
		String nombre,
		String descripcion,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean requiereAutorizacion,
		boolean requiereCredencial,
		BigDecimal copago,
		String moneda,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static PlanCatalogoView de(PlanCatalogo plan) {
		return new PlanCatalogoView(
				plan.getId(),
				plan.getFinanciadorCatalogoId(),
				plan.getCodigo(),
				plan.getNombre(),
				plan.getDescripcion(),
				plan.getVigenciaDesde(),
				plan.getVigenciaHasta(),
				plan.isRequiereAutorizacion(),
				plan.isRequiereCredencial(),
				plan.getCopago(),
				plan.getMoneda(),
				plan.isActive() ? "ACTIVO" : "INACTIVO",
				plan.getDeletedAt(),
				plan.getDeactivationReason(),
				plan.getVersion());
	}
}
