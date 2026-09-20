package com.akine.clinical.domain.exception;

/**
 * El plan de tratamiento pedido no existe dentro del alcance del actor (404).
 *
 * <p>Mismo criterio que {@link CasoClinicoNotAccessibleException} y por el mismo motivo: "no
 * existe", "es de otro tenant" y "su caso no es accesible" son <b>indistinguibles desde afuera a
 * proposito</b>. Un 403 confirmaria que la fila existe, y probar ids consecutivos alcanzaria para
 * censar cuantos tratamientos tiene en curso otro centro del SaaS.
 */
public class PlanTratamientoNotAccessibleException extends RuntimeException {

	private final long planId;

	public PlanTratamientoNotAccessibleException(long planId) {
		super("No existe un plan de tratamiento accesible con id " + planId);
		this.planId = planId;
	}

	public long getPlanId() {
		return planId;
	}
}
