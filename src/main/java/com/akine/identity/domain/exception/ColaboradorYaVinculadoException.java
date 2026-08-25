package com.akine.identity.domain.exception;

/**
 * La cuenta ya tiene un vinculo con esa organizacion en ese alcance.
 *
 * <p>Se traduce a {@code 409 colaborador-ya-vinculado}. Llega por dos caminos:
 *
 * <ul>
 *   <li><b>Al invitar</b>, cuando el administrador invita a alguien que ya trabaja ahi. Le
 *       ahorra al invitado un correo que no significa nada.</li>
 *   <li><b>Al aceptar</b>, cuando entre la invitacion y la respuesta alguien dio de alta a esa
 *       persona por el camino directo. La invitacion se cancela con ese motivo en el mismo
 *       acto: dejarla PENDIENTE la volveria un enlace que nunca va a funcionar.</li>
 * </ul>
 */
public class ColaboradorYaVinculadoException extends RuntimeException {

	public ColaboradorYaVinculadoException() {
		super("La cuenta ya esta vinculada a esta organizacion");
	}
}
