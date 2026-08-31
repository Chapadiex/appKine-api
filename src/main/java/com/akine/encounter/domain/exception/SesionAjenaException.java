package com.akine.encounter.domain.exception;

/**
 * Alguien intenta editar la sesion de otro profesional. <b>409</b>, con tipo propio.
 *
 * <p>No es 403: quien opera <b>si</b> tiene {@code sesion:register} en esa sede. Lo que no tiene es
 * la propiedad de esta atencion. Un 403 mandaria a la pantalla a decir "no tenes permiso", que es
 * falso y ademas manda al usuario a pedirle un permiso que ya tiene.
 */
public class SesionAjenaException extends RuntimeException {

	private final Long sesionId;
	private final Long profesionalMembershipId;

	public SesionAjenaException(Long sesionId, Long profesionalMembershipId) {
		super("La sesion " + sesionId + " la atiende otro profesional");
		this.sesionId = sesionId;
		this.profesionalMembershipId = profesionalMembershipId;
	}

	public Long getSesionId() {
		return sesionId;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}
}
