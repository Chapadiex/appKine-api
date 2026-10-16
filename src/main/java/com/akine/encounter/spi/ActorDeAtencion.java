package com.akine.encounter.spi;

/**
 * Quien pide una atencion de origen grupal, visto desde {@code encounter} (AKINE-08.05).
 *
 * <p>Existe porque {@code encounter.application.OperatingActor} es privado del modulo. Lleva la
 * <b>justificacion de acceso</b> de DP-03 tal como llego en {@code X-Justificacion-Acceso}: no se
 * interpreta aca ni en el llamador, la decide la politica clinica de {@code clinical}.
 */
public record ActorDeAtencion(
		long accountId,
		boolean platformAdmin,
		Long organizationId,
		Long consultorioId,
		String justificacion) {
}
