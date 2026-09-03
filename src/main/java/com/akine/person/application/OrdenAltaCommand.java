package com.akine.person.application;

import java.time.LocalDate;

/**
 * Alta de una orden medica (RF-M17-001).
 *
 * <p>{@code coberturaId} es opcional a proposito: una prescripcion la firma un medico, no un
 * financiador. Ver {@code OrdenMedica}.
 *
 * <p>{@code vigenciaDesde} nulo significa "desde la emision", que es el caso normal de un papel
 * que se presenta el mismo dia. No es un default arbitrario: una orden nunca puede valer antes de
 * escribirse, asi que la fecha de emision es el unico inicio que no puede ser incorrecto.
 */
public record OrdenAltaCommand(
		Long coberturaId,
		String numero,
		String profesionalEmisor,
		String matriculaEmisor,
		LocalDate fechaEmision,
		String indicacion,
		Integer sesionesPrescriptas,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String observaciones) {
}
