package com.akine.encounter.domain.exception;

/**
 * El profesional declarado como co-atendiente no tiene membership vigente en esa sede. <b>409.</b>
 *
 * <p>Es 409 y no 403: quien <b>opera</b> tiene permiso —es el dueño de la sesion y ya paso el
 * control—. Lo que no sirve es el dato que declaro. Un 403 mandaria a la pantalla a decir "no
 * tenes permiso", que es falso y ademas manda al usuario a pedir un permiso que ya tiene. Es el
 * mismo reparto que {@code SesionAjenaException}.
 */
public class ProfesionalNoAsignableException extends RuntimeException {

	private final long membershipId;

	public ProfesionalNoAsignableException(long membershipId) {
		super("El profesional " + membershipId + " no tiene membership vigente en esa sede");
		this.membershipId = membershipId;
	}

	public long getMembershipId() {
		return membershipId;
	}
}
