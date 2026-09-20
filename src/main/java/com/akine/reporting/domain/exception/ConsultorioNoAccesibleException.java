package com.akine.reporting.domain.exception;

/**
 * La sede no existe o es de otro tenant. <b>404 siempre, nunca 403</b>.
 *
 * <p>Un 403 confirmaria que la sede existe, y bastaria probar ids consecutivos para enumerar las
 * sedes del SaaS. Regla fijada en 01.01 y ADR-0018.
 */
public class ConsultorioNoAccesibleException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNoAccesibleException(long consultorioId) {
		super("Consultorio no accesible: " + consultorioId);
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
