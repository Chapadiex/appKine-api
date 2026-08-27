package com.akine.offering.domain.exception;

/**
 * La sede existe y el actor la puede ver, pero esta dada de baja y no admite ofertas nuevas (409).
 *
 * <p>409 y no 404: a diferencia de {@link ConsultorioNoAccesibleException}, aca no hay nada que
 * ocultar — la sede es del propio tenant del actor, que ya la conoce. Lo que no admite la
 * operacion es su ESTADO. Es RN-M03-003 aplicada un nivel mas abajo: una sede dada de baja no
 * origina hechos nuevos, y una oferta nueva es un hecho nuevo. Mismo tratamiento, y mismo motivo,
 * que {@code ConsultorioNotOperableException} en 02.02 para el alta de un espacio.
 *
 * <p><b>Solo bloquea el ALTA.</b> Editar o dar de baja una oferta de una sede inactiva sigue
 * permitido: RF-M03-004 exige que una sede dada de baja siga respondiendo por lo que ya paso ahi,
 * y cerrar tambien esos caminos dejaria a un centro sin poder ordenar su propio historico.
 */
public class ConsultorioNoOperableException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNoOperableException(long consultorioId) {
		super("La sede " + consultorioId + " esta dada de baja: no admite ofertas nuevas");
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
