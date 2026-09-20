package com.akine.clinical.domain.exception;

/**
 * Se intento cambiar el contenido de un plan FINALIZADO (409, no 404 y no 403).
 *
 * <p><b>No es 404</b>: el plan existe y se sigue leyendo entero, con todas sus versiones y su
 * historial. Eso es lo que distingue "termino" de "no existio" (regla maestra 10).
 *
 * <p><b>No es 403</b>: quien opera tiene {@code hc:write}. Lo que no admite cambios es el estado.
 * Un 403 lo mandaria a pedirle a su administrador un permiso que ya tiene.
 *
 * <p>La accion que la pantalla tiene que ofrecer es <b>crear un plan nuevo</b>: un plan finalizado
 * no se reabre. Modificar en silencio un tratamiento terminado es historia clinica reescrita, y
 * ADR-0011 lo prohibe.
 */
public class PlanNoEditableException extends RuntimeException {

	private final Long planId;
	private final String estado;

	public PlanNoEditableException(Long planId, String estado) {
		super("El plan de tratamiento " + planId + " esta " + estado + " y no admite cambios");
		this.planId = planId;
		this.estado = estado;
	}

	public Long getPlanId() {
		return planId;
	}

	public String getEstado() {
		return estado;
	}
}
