package com.akine.clinical.domain.exception;

/**
 * Se intento editar o agregar algo a un caso cerrado (409, no 404 y no 403).
 *
 * <p><b>No es 404</b>: el caso existe y se sigue leyendo entero, con todo su historial — eso es lo
 * que distingue "termino" de "no existio" (regla maestra 10).
 *
 * <p><b>No es 403</b>: quien opera tiene {@code hc:write}. Lo que no admite la operacion es el
 * estado del caso. Un 403 lo mandaria a pedirle a su administrador un permiso que ya tiene.
 *
 * <p>La accion correcta que la pantalla tiene que ofrecer es <b>reabrir con motivo</b>, que queda
 * en el historial (RF-M10-006). Editar en silencio un caso terminado es historia clinica
 * reescrita, y ADR-0011 lo prohibe.
 */
public class CasoClinicoCerradoException extends RuntimeException {

	private final Long casoClinicoId;

	public CasoClinicoCerradoException(Long casoClinicoId) {
		super("El caso clinico " + casoClinicoId + " esta cerrado y no admite cambios");
		this.casoClinicoId = casoClinicoId;
	}

	public Long getCasoClinicoId() {
		return casoClinicoId;
	}
}
