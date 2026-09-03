package com.akine.person.application;

import java.time.LocalDate;

/**
 * Edicion de una orden medica. Lo que llega nulo no se toca.
 *
 * <p>No estan {@code personaId} ni {@code coberturaId}, y no es un olvido: son inmutables en la
 * entidad. Mudar una orden de paciente reescribiria quien presento que papel.
 *
 * <p>{@code expectedVersion} es obligatorio: una version vieja produce 409 en vez de pisar el
 * cambio ajeno en silencio.
 */
public record OrdenEdicionCommand(
		String numero,
		String profesionalEmisor,
		String matriculaEmisor,
		LocalDate fechaEmision,
		String indicacion,
		Integer sesionesPrescriptas,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String observaciones,
		long expectedVersion) {
}
