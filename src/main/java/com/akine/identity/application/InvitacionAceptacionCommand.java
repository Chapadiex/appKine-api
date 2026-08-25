package com.akine.identity.application;

/**
 * Lo que llega al aceptar una invitacion (RF-M05-002).
 *
 * <p>Los tres campos de alta son <b>opcionales y condicionales</b>: hacen falta solo cuando el
 * invitado todavia no tiene cuenta. Si ya la tiene, mandarlos no cambia nada — no se le
 * reescriben el nombre ni la contrasena a alguien por aceptar una invitacion, que seria una via
 * de tomarle la cuenta a otro con solo invitarlo.
 *
 * @param token    token del enlace, en claro. Nunca se persiste ni se loguea
 * @param nombre   nombre del invitado, si hay que crear la cuenta
 * @param apellido apellido del invitado, si hay que crear la cuenta
 * @param password contrasena elegida, si hay que crear la cuenta
 */
public record InvitacionAceptacionCommand(
		String token, String nombre, String apellido, String password) {
}
