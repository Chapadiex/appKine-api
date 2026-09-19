package com.akine.clinical.domain.exception;

/**
 * El caso clinico pedido no existe dentro del alcance del actor (404).
 *
 * <p>Mismo criterio que {@link EntradaClinicaNotAccessibleException} y por el mismo motivo, que en
 * este modulo pesa mas que en ningun otro: "no existe", "es de otro tenant" y "es de otra
 * historia" son <b>indistinguibles desde afuera a proposito</b>. Un 403 confirmaria que la fila
 * existe, y probar ids consecutivos alcanzaria para censar cuantos casos clinicos tiene otro
 * centro del SaaS — que deja de ser un problema de aislamiento y pasa a ser uno de privacidad.
 */
public class CasoClinicoNotAccessibleException extends RuntimeException {

	private final long casoClinicoId;

	public CasoClinicoNotAccessibleException(long casoClinicoId) {
		super("No existe un caso clinico accesible con id " + casoClinicoId);
		this.casoClinicoId = casoClinicoId;
	}

	public long getCasoClinicoId() {
		return casoClinicoId;
	}
}
