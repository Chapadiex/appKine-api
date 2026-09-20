package com.akine.billing.domain.exception;

/**
 * El egreso no existe, o es de otro tenant. <b>404</b>, nunca 403.
 *
 * <p>Un 403 confirmaria que existe, y bastaria probar ids consecutivos para enumerar los egresos
 * del SaaS entero.
 */
public class EgresoNotAccessibleException extends RuntimeException {

	private final Long egresoId;

	public EgresoNotAccessibleException(Long egresoId) {
		super("El egreso " + egresoId + " no existe en este contexto");
		this.egresoId = egresoId;
	}

	public Long getEgresoId() {
		return egresoId;
	}
}
