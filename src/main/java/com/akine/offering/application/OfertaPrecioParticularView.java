package com.akine.offering.application;

import com.akine.offering.domain.OfertaPrecioParticular;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un precio particular de la oferta, para la grilla de vigencias (B-3, RF-M16-009). */
public record OfertaPrecioParticularView(
		long id,
		long ofertaId,
		BigDecimal importe,
		String moneda,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	static OfertaPrecioParticularView de(OfertaPrecioParticular p, LocalDate hoy) {
		return new OfertaPrecioParticularView(
				p.getId(),
				p.getOfertaId(),
				p.getImporte(),
				p.getMoneda(),
				p.getVigenciaDesde(),
				p.getVigenciaHasta(),
				p.aplicaEl(hoy),
				p.isActive() ? "ACTIVO" : "INACTIVO",
				p.getDeletedAt(),
				p.getDeactivationReason(),
				p.getVersion());
	}
}
