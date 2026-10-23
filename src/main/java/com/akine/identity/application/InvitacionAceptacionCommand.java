package com.akine.identity.application;

/**
 * Lo que llega al aceptar una invitacion (RF-M05-002).
 *
 * <p>Los campos de alta son <b>opcionales y condicionales</b>: hacen falta solo cuando el
 * invitado todavia no tiene cuenta. Si ya la tiene, mandarlos no cambia nada — no se le
 * reescriben el nombre ni la contrasena a alguien por aceptar una invitacion, que seria una via
 * de tomarle la cuenta a otro con solo invitarlo. Lo mismo los terminos (DP-45): se exigen y se
 * registran solo si la aceptacion crea la cuenta.
 *
 * @param token           token del enlace, en claro. Nunca se persiste ni se loguea
 * @param nombre          nombre del invitado, si hay que crear la cuenta
 * @param apellido        apellido del invitado, si hay que crear la cuenta
 * @param password        contrasena elegida, si hay que crear la cuenta
 * @param aceptaTerminos  si acepto los terminos vigentes, si hay que crear la cuenta
 * @param versionTerminos version de los terminos aceptados, si hay que crear la cuenta
 */
public record InvitacionAceptacionCommand(
		String token,
		String nombre,
		String apellido,
		String password,
		Boolean aceptaTerminos,
		String versionTerminos) {

	/** Aceptacion sin datos de terminos, la de antes de DP-45. */
	public InvitacionAceptacionCommand(
			String token, String nombre, String apellido, String password) {
		this(token, nombre, apellido, password, null, null);
	}
}
