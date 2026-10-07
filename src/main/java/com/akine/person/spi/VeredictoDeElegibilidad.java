package com.akine.person.spi;

import java.util.List;

/**
 * El veredicto de la elegibilidad administrativa, para otro modulo.
 *
 * @param elegible   la documentacion que el convenio exige esta presente
 * @param motivo     por que no hubo requisitos que evaluar (por ejemplo
 *                   {@code COBERTURA_PARTICULAR} o {@code SIN_CONVENIO_VIGENTE}); {@code null}
 *                   cuando se evaluaron
 * @param convenioId convenio vivo que fijo los requisitos, o {@code null} si no hubo
 * @param faltantes  un texto por requisito faltante, con el tipo adelante
 *                   ({@code "ORDEN: ..."}); vacia si es elegible
 */
public record VeredictoDeElegibilidad(
		boolean elegible,
		String motivo,
		Long convenioId,
		List<String> faltantes) {

	public VeredictoDeElegibilidad {
		faltantes = faltantes == null ? List.of() : List.copyOf(faltantes);
	}
}
