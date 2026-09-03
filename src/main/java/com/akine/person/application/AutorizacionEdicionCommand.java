package com.akine.person.application;

import java.time.LocalDate;

/**
 * Edicion de una autorizacion. Lo que llega nulo no se toca.
 *
 * <p>No estan la cobertura, la practica ni el estado. Las dos primeras son inmutables —cambiarlas
 * es OTRA autorizacion, porque reescribirian contra que se autorizo—; el estado se mueve con la
 * accion de {@code AutorizacionService#resolver} y nunca por asignacion.
 */
public record AutorizacionEdicionCommand(
		String numero,
		Long ordenMedicaId,
		Integer cantidadAutorizada,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String observaciones,
		long expectedVersion) {
}
