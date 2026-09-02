package com.akine.person.domain.exception;

/**
 * La cobertura no existe, es de otra organizacion, o es de otra persona (404).
 *
 * <p>Los tres casos responden lo MISMO, y eso es deliberado: distinguirlos confirmaria que ese id
 * existe, y bastaria recorrer numeros para averiguar cuantas coberturas tiene el padron de un
 * centro. Mismo criterio que {@code PersonaNotAccessibleException} y que toda la familia de 404
 * del repositorio (ADR-0018).
 */
public class CoberturaNotAccessibleException extends RuntimeException {

	private final long coberturaId;

	public CoberturaNotAccessibleException(long coberturaId) {
		super("La cobertura " + coberturaId + " no existe o no es accesible");
		this.coberturaId = coberturaId;
	}

	public long getCoberturaId() {
		return coberturaId;
	}
}
