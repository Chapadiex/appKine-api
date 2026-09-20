package com.akine.clinical.domain.exception;

/**
 * Una oferta que se quiso planificar no existe en la sede o no esta habilitada hoy (409).
 *
 * <p><b>Por que no se reusa {@link OfertaNoVigenteException}</b>, que es lo primero que se va a
 * preguntar quien lea esto: aquella es del alta de Caso y la pantalla que la recibe esta eligiendo
 * <b>una</b> oferta. Esta llega desde el armado de un plan, donde el profesional carga <b>varias</b>
 * practicas de una vez, y lleva {@code ofertaId} para que la pantalla pueda señalar cual de todas
 * es la que no entra en vez de rechazar el formulario entero sin decir por que.
 *
 * <p><b>409 y no 404</b>, mismo criterio que la oferta no vigente: la oferta existe y quien la
 * eligio la esta viendo en una lista. Lo que corresponde ofrecerle es reactivarla o elegir otra.
 *
 * <p>Que la oferta se de de baja <b>despues</b> de planificar NO invalida el plan: es la regla que
 * 02.06 dejo fijada —la baja de un servicio no cascadea, solo impide crear nuevos— y por eso esta
 * excepcion aparece al escribir una version y nunca al leerla.
 */
public class OfertaNoHabilitadaException extends RuntimeException {

	private final long ofertaId;

	public OfertaNoHabilitadaException(long ofertaId) {
		super("La oferta " + ofertaId + " no esta habilitada en esta sede y no se puede planificar");
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
