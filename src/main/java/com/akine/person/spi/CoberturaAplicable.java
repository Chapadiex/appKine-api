package com.akine.person.spi;

import com.akine.contracting.spi.ResolucionDeArancel;

import java.time.LocalDate;

/**
 * Una cobertura que aplica a la practica en la fecha.
 *
 * @param referencia financiador y plan CONGELADOS en la cobertura, no los vivos del catalogo
 * @param resolucion siempre {@code estaResuelta()}
 * @param credencialVencida solo alerta: no excluye la cobertura
 */
public record CoberturaAplicable(
		long coberturaId,
		boolean principal,
		ReferenciaCongelada referencia,
		ResolucionDeArancel resolucion,
		boolean credencialVencida,
		LocalDate credencialVigenciaHasta) {
}
