package com.akine.identity.application;

/**
 * Que quedo despues de aceptar una invitacion (RF-M05-002).
 *
 * @param cuentaId       cuenta que acepto, existente o recien creada
 * @param membershipId   vinculo creado
 * @param organizationId organizacion a la que quedo vinculada
 * @param consultorioId  sede del vinculo, o {@code null} si es de alcance organizacion
 * @param cuentaCreada   {@code true} si la cuenta nacio en este acto. Es lo que le permite a la
 *                       pantalla decir "ya podes entrar con la contrasena que elegiste" en vez
 *                       de "entra con tu cuenta de siempre", que son dos mensajes distintos para
 *                       dos personas distintas
 */
public record ResultadoAceptacion(
		long cuentaId,
		long membershipId,
		long organizationId,
		Long consultorioId,
		boolean cuentaCreada) {
}
