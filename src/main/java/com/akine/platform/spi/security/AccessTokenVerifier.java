package com.akine.platform.spi.security;

import java.util.Optional;

/**
 * Verificacion del access token: firma, emisor, formato y vencimiento.
 *
 * <p>Lo consume el filtro de autenticacion de {@code platform}. Lo implementa
 * {@code identity.infrastructure}, que es donde vive el secreto de firma.
 *
 * <p><b>Un solo resultado para todas las formas de invalidez.</b> Firma alterada, token
 * vencido, emisor ajeno, base64 roto o claims faltantes devuelven todos
 * {@link Optional#empty()}. Distinguirlos en la respuesta le diria a quien esta probando
 * tokens cual de sus manipulaciones estuvo mas cerca de funcionar; la diferencia va al log,
 * no al cliente.
 */
public interface AccessTokenVerifier {

	/**
	 * Devuelve los claims si el token es autentico y esta vigente ahora.
	 *
	 * <p>Nunca lanza por un token malformado: recibe entrada de red y un token cualquiera no
	 * es una condicion excepcional del servidor.
	 *
	 * @param compactToken el JWT tal como llego, sin el prefijo {@code Bearer}
	 */
	Optional<AccessTokenClaims> verify(String compactToken);
}
