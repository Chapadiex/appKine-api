package com.akine.activity.application;

import com.akine.activity.domain.ProductoServicio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un producto de pack del catalogo de la sede (RF-M29-001). */
public record ProductoView(
		long id,
		long consultorioId,
		long ofertaId,
		String nombre,
		String descripcion,
		String tipo,
		int creditos,
		BigDecimal precio,
		String moneda,
		int vigenciaDias,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean active,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static ProductoView de(ProductoServicio p) {
		return new ProductoView(
				p.getId(), p.getConsultorioId(), p.getOfertaId(), p.getNombre(), p.getDescripcion(),
				p.getTipo().name(), p.getCreditos(), p.getPrecio(), p.getMoneda(),
				p.getVigenciaDias(), p.getVigenciaDesde(), p.getVigenciaHasta(), p.isActive(),
				p.getDeletedAt(), p.getDeactivationReason(), p.getVersion());
	}
}
