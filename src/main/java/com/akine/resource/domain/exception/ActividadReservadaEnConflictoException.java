package com.akine.resource.domain.exception;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

/**
 * Una apertura de agenda choca con algo que la misma persona ya tiene reservado en otra sede —un turno
 * o una clase, como principal o adicional— (disponibilidad central, contrato 0.158.0). <b>409
 * {@code recurso-ocupado}, siempre</b>: ni la sustitucion explicita lo salva, porque lo reservado no
 * se mueve en silencio.
 */
public class ActividadReservadaEnConflictoException extends RuntimeException {

	private final transient ConflictoDeAgenda conflicto;

	public ActividadReservadaEnConflictoException(ConflictoDeAgenda conflicto) {
		super(conflicto.detalle("No es posible abrirle la agenda en ese horario."));
		this.conflicto = conflicto;
	}

	public ConflictoDeAgenda getConflicto() {
		return conflicto;
	}
}
