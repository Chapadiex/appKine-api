package com.akine.platform.spi.security;

/**
 * Un access token recien emitido: el valor serializado y lo que ese valor afirma.
 *
 * <p>Se devuelven juntos porque quien emite necesita las dos cosas —el string para el cuerpo
 * de la respuesta y los claims para la auditoria— y volver a parsear el token para recuperar
 * lo que uno mismo acaba de escribir es trabajo inutil y una fuente de divergencia.
 *
 * <p>El nombre no termina en {@code Response} a proposito: no es un DTO de la API, es un
 * contrato entre modulos. Los DTO viven en {@code api.dto} y los verifica ArchUnit.
 *
 * @param value            el JWT compacto, listo para el header {@code Authorization: Bearer}
 * @param claims           lo que el token afirma
 * @param expiresInSeconds segundos de vida restantes al momento de emitir, para que el cliente
 *                         programe el refresh sin tener que leer el token
 */
public record IssuedAccessToken(String value, AccessTokenClaims claims, long expiresInSeconds) {
}
