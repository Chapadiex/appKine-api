package com.akine.identity.domain.exception;

import com.akine.identity.domain.EstadoInvitacion;

/**
 * Se intento resolver una invitacion que ya no esta PENDIENTE.
 *
 * <p>Se traduce a {@code 409 invitacion-ya-resuelta}. Es el caso de dos personas operando la
 * misma invitacion a la vez: el administrador la cancela mientras el invitado la acepta, o el
 * invitado abre el enlace dos veces. Responder 200 en silencio le diria a la segunda que su
 * accion tuvo efecto cuando lo que quedo registrado es la primera.
 */
public class InvitacionNoPendienteException extends RuntimeException {

	private final transient Long invitacionId;
	private final transient EstadoInvitacion estado;

	public InvitacionNoPendienteException(Long invitacionId, EstadoInvitacion estado) {
		super("La invitacion " + invitacionId + " ya esta " + estado);
		this.invitacionId = invitacionId;
		this.estado = estado;
	}

	public Long getInvitacionId() {
		return invitacionId;
	}

	public EstadoInvitacion getEstado() {
		return estado;
	}
}
