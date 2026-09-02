package com.akine.person.domain.exception;

/**
 * El paciente ya tiene una cobertura activa del MISMO plan con la vigencia solapada (409).
 *
 * <p>Es un duplicado, no una segunda cobertura. Ningun unique lo puede expresar: MySQL 8.4 no
 * tiene exclusion constraints y 01-01..06-30 y 03-01..12-31 no comparten ningun valor de columna.
 * Lo hace cumplir el lock de {@code cobertura_persona_lock}, en {@code READ_COMMITTED}.
 *
 * <p><b>Dos coberturas de financiadores DISTINTOS solapadas son legitimas</b> y esta excepcion no
 * las alcanza: un paciente con obra social y prepaga a la vez es el caso normal. Lo que se elige
 * para una atencion lo decide la marca principal.
 */
public class CoberturaSuperpuestaException extends RuntimeException {

	private final long coberturaExistenteId;

	public CoberturaSuperpuestaException(long coberturaExistenteId) {
		super("Ya existe una cobertura vigente de ese plan que se solapa con la nueva");
		this.coberturaExistenteId = coberturaExistenteId;
	}

	public long getCoberturaExistenteId() {
		return coberturaExistenteId;
	}
}
