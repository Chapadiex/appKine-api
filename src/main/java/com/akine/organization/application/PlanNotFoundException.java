package com.akine.organization.application;

/**
 * El plan pedido no existe o ya no es contratable.
 *
 * <p>Los dos casos son el mismo a proposito: un plan retirado sigue existiendo —hay
 * suscripciones historicas que lo referencian y RN-M01-002 prohibe perder esa historia— pero
 * no se puede contratar de nuevo. Para quien intenta contratarlo, "retirado" e "inexistente"
 * son indistinguibles, y distinguirlos solo filtraria la evolucion comercial del SaaS.
 * Se responde {@code 404}.
 */
public class PlanNotFoundException extends RuntimeException {

	private final transient String planCode;

	public PlanNotFoundException(String planCode) {
		super("Plan no encontrado o no contratable");
		this.planCode = planCode;
	}

	/** Para el log correlacionado. */
	public String getPlanCode() {
		return planCode;
	}
}
