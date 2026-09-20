package com.akine.resource.domain.exception;

/**
 * La definicion de medicion no existe, o es contextual de otro tenant (<b>404 siempre</b>).
 *
 * <p>Las dos causas colapsan en un solo {@code type} y en un solo status, que es la regla de
 * 01.01 y ADR-0018: un 403 confirmaria que ese id existe, y bastaria recorrer numeros para
 * censar que tests propios tiene cargados cada centro del SaaS.
 *
 * <p><b>Una definicion dada de baja NO produce esta excepcion</b>: se sigue leyendo con 200.
 * RN-M06-001 y RN-M06-002 exigen que un concepto historico siga resolviendo, y responder "no
 * existe" seria borrar historia por la puerta de atras.
 */
public class MedicionDefinicionNoAccesibleException extends RuntimeException {

	private final long definicionId;

	public MedicionDefinicionNoAccesibleException(long definicionId) {
		super("Definicion de medicion no accesible: " + definicionId);
		this.definicionId = definicionId;
	}

	public long getDefinicionId() {
		return definicionId;
	}
}
