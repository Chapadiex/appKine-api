package com.akine.resource.domain.exception;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

import java.util.List;

/**
 * Una apertura de agenda se superpone con el horario de atencion de la misma persona en otra sede
 * (disponibilidad central, contrato 0.158.0): doble oferta. <b>409 {@code bloque-solapado}</b>, con
 * cada franja superpuesta. Se resuelve pidiendo la sustitucion explicita
 * ({@code sustituirDisponibilidadSuperpuesta}), que cierra la otra sede en esa franja.
 */
public class DisponibilidadSuperpuestaException extends RuntimeException {

	private final transient List<ConflictoDeAgenda> superposiciones;

	public DisponibilidadSuperpuestaException(List<ConflictoDeAgenda> superposiciones) {
		super(superposiciones.getFirst().detalle(
				"No es posible ofrecer disponibilidad simultanea en dos sedes: pida la sustitucion para cerrar "
						+ "la otra franja."));
		this.superposiciones = List.copyOf(superposiciones);
	}

	public List<ConflictoDeAgenda> getSuperposiciones() {
		return superposiciones;
	}
}
