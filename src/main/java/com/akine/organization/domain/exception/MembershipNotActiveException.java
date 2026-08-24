package com.akine.organization.domain.exception;

import com.akine.organization.domain.MembershipEstado;

/**
 * La transicion pedida no sale del estado en el que la membership esta ahora.
 *
 * <p>Es la carrera que reemplazo a la de aceptacion doble de invitaciones cuando D-1 dejo la
 * invitacion fuera: dos administradores operando la misma membership al mismo tiempo. Las dos
 * transacciones quedan serializadas por el bloqueo del tenant, asi que la segunda entra con el
 * estado YA cambiado y decide contra el —no contra el que el actor creia—. Si la membership
 * quedo {@code REVOCADA}, cambiarle el rol es un conflicto y no un no-op silencioso: dos
 * administradores tienen que enterarse de que el otro llego primero.
 *
 * <p>409, con los dos estados publicados: el cliente los conoce o los envio, asi que no es
 * informacion nueva, y sin ellos el mensaje seria inaccionable.
 */
public class MembershipNotActiveException extends RuntimeException {

	private final Long membershipId;
	private final MembershipEstado estadoActual;
	private final MembershipEstado estadoPedido;

	public MembershipNotActiveException(
			Long membershipId, MembershipEstado estadoActual, MembershipEstado estadoPedido) {
		super("La membership esta en " + estadoActual + " y no admite pasar a " + estadoPedido);
		this.membershipId = membershipId;
		this.estadoActual = estadoActual;
		this.estadoPedido = estadoPedido;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public MembershipEstado getEstadoActual() {
		return estadoActual;
	}

	public MembershipEstado getEstadoPedido() {
		return estadoPedido;
	}
}
