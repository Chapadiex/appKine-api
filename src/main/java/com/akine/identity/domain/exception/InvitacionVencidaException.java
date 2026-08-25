package com.akine.identity.domain.exception;

/**
 * El enlace de la invitacion existe pero ya vencio.
 *
 * <p>Se traduce a {@code 409 invitacion-vencida}, y <b>no</b> a 404 ni a "token invalido".
 * La diferencia importa: un token que no existe y uno que expiro tienen salidas distintas
 * —para el segundo alcanza con pedirle al administrador que reenvie— y confundirlos manda al
 * invitado a reportar que el sistema esta roto.
 *
 * <p>Que esto no sea una respuesta uniforme es deliberado y no contradice ADR-0018: quien
 * presenta el token ya demostro que llega al buzon del invitado, asi que no hay nada que
 * enumerar. Un token inventado si recibe la respuesta uniforme de siempre.
 */
public class InvitacionVencidaException extends RuntimeException {

	public InvitacionVencidaException() {
		super("La invitacion vencio");
	}
}
