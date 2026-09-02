package com.akine.contracting.application;

import com.akine.contracting.domain.ModalidadConvenio;

import java.time.LocalDate;

/**
 * Alta de un convenio (RF-M16-001).
 *
 * <p>La sede no viaja aca: sale de la ruta y del contexto validado, nunca de un parametro del
 * cliente. El financiador y el plan si, porque son la eleccion del administrador.
 *
 * @param vigenciaHasta ultimo dia INCLUSIVE. {@code null} = sin fin previsto
 * @param moneda        ISO 4217 de los aranceles de este convenio. Obligatorio
 */
public record ConvenioAltaCommand(
		long financiadorId,
		long planId,
		String codigo,
		String nombre,
		ModalidadConvenio modalidad,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String moneda,
		Boolean requiereOrden,
		Boolean requiereAutorizacion,
		Boolean requiereCredencial,
		Integer limiteSesionesMensual,
		String documentacionRequerida,
		String observaciones) {
}
