package com.akine.activity.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Alta de un producto de pack (RF-M29-001). */
public record CrearProductoCommand(
		long ofertaId,
		String nombre,
		String descripcion,
		int creditos,
		BigDecimal precio,
		String moneda,
		int vigenciaDias,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta) {
}
