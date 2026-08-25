package com.akine.identity.domain.exception;

/**
 * Ya hay una invitacion PENDIENTE para ese email en ese alcance.
 *
 * <p>Se traduce a {@code 409 invitacion-pendiente-duplicada}. Emitir la segunda dejaria dos
 * enlaces validos para la misma persona y el mismo puesto: el invitado elige cual usar y el
 * administrador no sabe cual mando. Lo que el administrador quiere en ese caso es
 * <b>reenviar</b>, que conserva el pedido y rota el token.
 *
 * <p>Notar que una invitacion <b>vencida</b> tambien cae aca: sigue siendo PENDIENTE en la
 * columna —expirar no es un estado, ver {@code EstadoInvitacion}— y por eso el camino tambien
 * es el reenvio.
 */
public class InvitacionPendienteDuplicadaException extends RuntimeException {

	private final transient String emailNormalizado;

	public InvitacionPendienteDuplicadaException(String emailNormalizado) {
		super("Ya hay una invitacion pendiente para ese email");
		this.emailNormalizado = emailNormalizado;
	}

	public String getEmailNormalizado() {
		return emailNormalizado;
	}
}
