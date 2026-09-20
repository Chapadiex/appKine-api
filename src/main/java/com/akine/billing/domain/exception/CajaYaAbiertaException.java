package com.akine.billing.domain.exception;

/**
 * Ya hay una jornada abierta en esa sede. <b>409</b>.
 *
 * <p>A lo sumo una por sede: dos cajas abiertas sobre el mismo cajon fisico hacen que ningun arqueo
 * se pueda atribuir. Lo hace cumplir un unique de la base sobre una columna generada, no un
 * {@code if} — pero el servicio consulta antes para poder devolver el id de la que ya esta abierta,
 * que es lo que la pantalla necesita para llevar al operador ahi en vez de dejarlo trabado.
 */
public class CajaYaAbiertaException extends RuntimeException {

	private final long consultorioId;
	private final long jornadaAbiertaId;

	public CajaYaAbiertaException(long consultorioId, long jornadaAbiertaId) {
		super("La sede " + consultorioId + " ya tiene la jornada de caja " + jornadaAbiertaId
				+ " abierta");
		this.consultorioId = consultorioId;
		this.jornadaAbiertaId = jornadaAbiertaId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}

	public long getJornadaAbiertaId() {
		return jornadaAbiertaId;
	}
}
