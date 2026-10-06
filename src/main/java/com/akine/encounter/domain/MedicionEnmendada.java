package com.akine.encounter.domain;

/**
 * Una medicion tal como tiene que quedar despues de una enmienda (C-6).
 *
 * <p>Se identifica por {@code (definicionId, lateralidad)}, igual que {@code uk_sesion_medicion}:
 * una medicion no tiene identidad propia hacia afuera.
 */
public record MedicionEnmendada(
		long definicionId, LateralidadMedicion lateralidad, ValorMedido valor, String nota) {

	public MedicionEnmendada {
		lateralidad = lateralidad == null ? LateralidadMedicion.NO_APLICA : lateralidad;
	}
}
