package com.akine.activity.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edicion de un producto. {@code null} conserva el valor; {@code limpiarVigenciaHasta} deja la
 * ventana sin fin. {@code version} es la que el cliente leyo (control optimista).
 */
public record EditarProductoCommand(
		String nombre,
		String descripcion,
		Integer creditos,
		BigDecimal precio,
		String moneda,
		Integer vigenciaDias,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean limpiarVigenciaHasta,
		long version) {

	/** AKINE-09.02: ver {@link FechasAdmitidas}. */
	public EditarProductoCommand {
		FechasAdmitidas.exigir(vigenciaDesde, "vigenciaDesde");
		FechasAdmitidas.exigir(vigenciaHasta, "vigenciaHasta");
	}
}
