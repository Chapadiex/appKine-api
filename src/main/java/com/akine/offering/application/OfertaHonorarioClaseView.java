package com.akine.offering.application;

import com.akine.offering.domain.OfertaHonorarioClase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un honorario por clase de la oferta, para la grilla de vigencias (AKINE-08.09b). */
public record OfertaHonorarioClaseView(
		long id,
		long ofertaId,
		BigDecimal importePorParticipante,
		String moneda,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	static OfertaHonorarioClaseView de(OfertaHonorarioClase h, LocalDate hoy) {
		return new OfertaHonorarioClaseView(
				h.getId(),
				h.getOfertaId(),
				h.getImportePorParticipante(),
				h.getMoneda(),
				h.getVigenciaDesde(),
				h.getVigenciaHasta(),
				h.aplicaEl(hoy),
				h.isActive() ? "ACTIVO" : "INACTIVO",
				h.getDeletedAt(),
				h.getDeactivationReason(),
				h.getVersion());
	}
}
