package com.akine.person.domain.exception;

/**
 * La cobertura esta dada de baja y la operacion exige una vigente (409).
 *
 * <p>409 y no 404 por el mismo motivo que {@code PersonaInactivaException}: la cobertura existe y
 * se sigue leyendo con 200, porque los hechos que la referencian tienen que poder explicarse
 * (RN-M08-003). Lo que no admite la operacion es su estado.
 *
 * <p>Lleva la operacion porque "esto ya estaba dado de baja" y "esto no se puede editar porque
 * esta de baja" son dos acciones distintas para quien las recibe, y salen con {@code type}
 * distinto.
 */
public class CoberturaInactivaException extends RuntimeException {

	private final long coberturaId;
	private final String operacion;

	public CoberturaInactivaException(long coberturaId, String operacion) {
		super("La cobertura " + coberturaId + " esta dada de baja y no admite " + operacion);
		this.coberturaId = coberturaId;
		this.operacion = operacion;
	}

	public long getCoberturaId() {
		return coberturaId;
	}

	public String getOperacion() {
		return operacion;
	}
}
