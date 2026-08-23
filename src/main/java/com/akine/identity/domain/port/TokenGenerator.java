package com.akine.identity.domain.port;

/**
 * Fuente de los valores opacos que viajan en los enlaces de correo y en la cookie de refresh.
 *
 * <p>El puerto vive aca y la implementacion la aporta {@code identity.infrastructure}
 * ({@code SecureTokenGenerator}). La separacion importa: un test no puede depender de un
 * valor aleatorio, y {@code application} no debe poder elegir un generador debil.
 *
 * <p>Exigencia de la implementacion: {@code SecureRandom}, al menos 32 bytes de entropia, y
 * codificacion segura para URL. Con 256 bits de entropia el token no se adivina, y eso es lo
 * que permite guardarlo como SHA-256 sin salt (ver {@code TokenDigest}). Un generador
 * predecible aca no rompe una defensa: las rompe todas, porque el token ES la credencial.
 */
public interface TokenGenerator {

	/** Devuelve un token nuevo, seguro para URL. */
	String nuevoToken();

	/** Devuelve el identificador de una familia de refresh (UUID v4). */
	String nuevaFamilia();
}
