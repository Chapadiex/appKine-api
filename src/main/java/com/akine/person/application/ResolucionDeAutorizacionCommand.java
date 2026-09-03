package com.akine.person.application;

import com.akine.person.domain.AccionSobreAutorizacion;

import java.time.LocalDate;

/**
 * La respuesta del financiador aplicada a una autorizacion (RF-M17-001).
 *
 * <p>Es una ACCION y no un estado destino: ver {@code AccionSobreAutorizacion}. El conjunto de
 * transiciones posibles queda del lado del backend.
 *
 * <p>{@code cantidadAutorizada}, {@code vigenciaDesde} y {@code vigenciaHasta} solo se aplican al
 * APROBAR, y son lo que resuelve la <b>autorizacion parcial</b>: el financiador puede otorgar
 * menos de lo pedido, o para una ventana mas corta, y lo que vale es lo que concedio.
 */
public record ResolucionDeAutorizacionCommand(
		AccionSobreAutorizacion accion,
		String motivo,
		Integer cantidadAutorizada,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		long expectedVersion) {
}
