package com.akine.person.domain.exception;

/**
 * El paciente ya tiene otra cobertura PRINCIPAL activa con la vigencia solapada (409).
 *
 * <p>El criterio de aceptacion de la etapa pide que "la seleccion vigente sea determinista", y con
 * dos principales el mismo dia deja de serlo: el sistema tendria que elegir por un desempate
 * arbitrario —la mas nueva, la de id mas bajo— y esa eleccion no se podria explicar en el
 * mostrador.
 *
 * <p>Igual que {@link CoberturaSuperpuestaException}, ningun indice lo expresa: lo hace cumplir el
 * lock de {@code cobertura_persona_lock}. Lleva el id de la que ya es principal para que la
 * pantalla ofrezca finalizarla en vez de dejar al operador sin salida.
 */
public class CoberturaPrincipalSuperpuestaException extends RuntimeException {

	private final long coberturaPrincipalId;

	public CoberturaPrincipalSuperpuestaException(long coberturaPrincipalId) {
		super("El paciente ya tiene una cobertura principal vigente en ese periodo");
		this.coberturaPrincipalId = coberturaPrincipalId;
	}

	public long getCoberturaPrincipalId() {
		return coberturaPrincipalId;
	}
}
