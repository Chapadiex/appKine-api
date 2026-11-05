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

	/** Lo que se le dice a quien da de baja o achica un cierre y con eso vuelve la doble oferta. */
	public static final String AL_LIBERAR = "No es posible dar de baja o achicar el cierre: el profesional quedaria "
			+ "ofreciendo disponibilidad simultanea en dos sedes. Si el cierre se creo al sustituir por una apertura "
			+ "en esa sede, primero de de baja o achique esa apertura.";

	public DisponibilidadSuperpuestaException(List<ConflictoDeAgenda> superposiciones) {
		this(superposiciones, "No es posible ofrecer disponibilidad simultanea en dos sedes: pida la sustitucion "
				+ "para cerrar la otra franja.");
	}

	/** Con la segunda oracion del {@code detail} propia: la baja o el achique de un cierre ({@link #AL_LIBERAR}). */
	public DisponibilidadSuperpuestaException(List<ConflictoDeAgenda> superposiciones, String consecuencia) {
		super(superposiciones.getFirst().detalle(consecuencia));
		this.superposiciones = List.copyOf(superposiciones);
	}

	public List<ConflictoDeAgenda> getSuperposiciones() {
		return superposiciones;
	}
}
