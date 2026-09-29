package com.akine.clinical.domain.exception;

/**
 * El caso ya tiene un plan ocupando el lugar del vigente y no se puede activar otro (409).
 *
 * <h2>Cuando se lanza, y cuando NO</h2>
 *
 * <p><b>Solo cuando el que ocupa el lugar esta SUSPENDIDO.</b> Si el que ocupa esta ACTIVO, activar
 * otro plan lo finaliza automaticamente: es la conducta que RF-M11-006 pide y la que hace que
 * cambiar de abordaje sea una sola operacion.
 *
 * <p>Un plan suspendido es distinto: alguien freno ese tratamiento y quiere volver a el. Finalizarlo
 * en silencio convertiria "el paciente se va dos meses" en "el tratamiento termino", que es
 * exactamente la distincion que {@link com.akine.clinical.domain.EstadoPlan#SUSPENDIDO} existe para
 * conservar. La decision de darlo por terminado es de quien atiende, no del sistema.
 *
 * <h2>409 y no 400</h2>
 *
 * <p>El pedido es valido y lo que impide la operacion es el estado del caso, que puede cambiar: el
 * mismo pedido funciona en cuanto alguien finalice el plan frenado. Lleva el id y el numero del plan
 * que ocupa el lugar porque una pantalla que solo diga "no se puede" deja al profesional sin la
 * accion que corresponde — ir a ese plan y finalizarlo.
 */
public class PlanVivoEnElCasoException extends RuntimeException {

	private final long planQueOcupaId;
	private final int numeroDelPlanQueOcupa;

	public PlanVivoEnElCasoException(long planQueOcupaId, int numeroDelPlanQueOcupa) {
		super("El plan %d del caso esta suspendido y sigue ocupando el lugar del plan vigente."
				.formatted(numeroDelPlanQueOcupa));
		this.planQueOcupaId = planQueOcupaId;
		this.numeroDelPlanQueOcupa = numeroDelPlanQueOcupa;
	}

	public long getPlanQueOcupaId() {
		return planQueOcupaId;
	}

	public int getNumeroDelPlanQueOcupa() {
		return numeroDelPlanQueOcupa;
	}
}
