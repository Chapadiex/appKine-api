package com.akine.activity.application;

import com.akine.activity.domain.TipoProducto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de un producto (RF-M29-001; abono, RF-M29-007).
 *
 * @param tipo {@code PACK} o {@code ABONO}; {@code null} es {@code PACK}, que es lo que la API
 *             aceptaba antes de 08.08
 */
public record CrearProductoCommand(
		long ofertaId,
		String nombre,
		String descripcion,
		int creditos,
		BigDecimal precio,
		String moneda,
		int vigenciaDias,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		TipoProducto tipo) {

	/** Un pack, como antes de 08.08. */
	public CrearProductoCommand(
			long ofertaId, String nombre, String descripcion, int creditos, BigDecimal precio,
			String moneda, int vigenciaDias, LocalDate vigenciaDesde, LocalDate vigenciaHasta) {
		this(ofertaId, nombre, descripcion, creditos, precio, moneda, vigenciaDias, vigenciaDesde,
				vigenciaHasta, TipoProducto.PACK);
	}

	public TipoProducto tipoEfectivo() {
		return tipo == null ? TipoProducto.PACK : tipo;
	}
}
