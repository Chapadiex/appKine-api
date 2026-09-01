package com.akine.encounter.domain.exception;

/**
 * Se intenta editar una atencion ya cerrada. <b>409</b>.
 *
 * <p>Corregir lo que dice una sesion cerrada es una ENMIENDA, con su actor y su motivo, y eso es
 * AKINE-06.06 — fuera del Paquete B por DP-10. Hasta que exista, esto es fail-closed: es preferible
 * no poder corregir a corregir sin dejar rastro, porque lo segundo es historia clinica reescrita en
 * silencio.
 */
public class SesionCerradaException extends RuntimeException {

	private final Long sesionId;

	public SesionCerradaException(Long sesionId) {
		super("La sesion " + sesionId + " ya esta cerrada: corregirla exige una enmienda");
		this.sesionId = sesionId;
	}

	public Long getSesionId() {
		return sesionId;
	}
}
