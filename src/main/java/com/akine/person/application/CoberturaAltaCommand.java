package com.akine.person.application;

import com.akine.person.domain.TipoCobertura;

import java.time.LocalDate;

/**
 * Alta de una cobertura del paciente (RF-M08-001).
 *
 * <p><b>No lleva {@code financiadorId}</b>, y no es un olvido: el financiador sale de la
 * referencia congelada que devuelve el catalogo al resolver el {@code planId}. Aceptarlo en el
 * comando permitiria un alta cuyo financiador contradice al del plan, y esa contradiccion habria
 * que resolverla con una validacion mas en vez de hacerla inexpresable.
 *
 * @param planId  obligatorio si {@code tipo} es FINANCIADA, prohibido si es PARTICULAR
 * @param principal ausente se toma como {@code false}: marcar principal es una decision explicita
 */
public record CoberturaAltaCommand(
		TipoCobertura tipo,
		Long planId,
		String numeroAfiliado,
		LocalDate credencialVigenciaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		Boolean principal,
		String observaciones) {
}
