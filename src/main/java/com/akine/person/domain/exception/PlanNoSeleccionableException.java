package com.akine.person.domain.exception;

/**
 * El plan no se puede elegir para esa fecha (409). RN-M08-002 y RN-M15-002.
 *
 * <p>Es la traduccion del {@code Optional.empty} que devuelve
 * {@code CoberturaCatalogoDirectory#congelar}. Ese metodo <b>no lanza a proposito</b>: junta cinco
 * causas —el plan no existe, es de otro tenant, esta dado de baja, su financiador esta dado de
 * baja, o la fecha cae fuera de su vigencia— y deja que quien firma la cobertura decida que
 * responder. Esta clase es esa decision.
 *
 * <p><b>409 y no 404 aunque el plan no exista</b>, y aunque sea de otro tenant. Distinguirlos
 * volveria este endpoint un oraculo del catalogo ajeno: bastaria recorrer ids para saber que
 * planes tiene cargados otro centro del SaaS. Lo unico que el mensaje dice es que ese plan no se
 * puede elegir hoy, que es todo lo que el mostrador necesita saber.
 */
public class PlanNoSeleccionableException extends RuntimeException {

	private final long planId;

	public PlanNoSeleccionableException(long planId) {
		super("El plan " + planId + " no se puede elegir para esa fecha");
		this.planId = planId;
	}

	public long getPlanId() {
		return planId;
	}
}
