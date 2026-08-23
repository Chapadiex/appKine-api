package com.akine.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Huella con la que se guardan y se buscan los tokens de un solo uso y los refresh.
 *
 * <p><b>El valor plano del token no se persiste nunca</b> (RN-M02-003). Lo unico que llega a
 * la base es este SHA-256 en hexadecimal; la busqueda se hace hasheando lo que presenta el
 * cliente y comparandolo contra el unique. Consecuencia practica: un volcado de las tablas
 * —o un backup filtrado— no permite activar una cuenta ni resetear una contrasena.
 *
 * <p><b>Por que SHA-256 sin salt y sin iteraciones, cuando para contrasenas eso seria un
 * error grave.</b> El salt y el costo existen para frenar el ataque por diccionario contra un
 * secreto que eligio una persona y que por lo tanto tiene poca entropia. Estos tokens son 32
 * bytes de {@code SecureRandom}: no hay diccionario que los contenga y no hay tabla arcoiris
 * que se pueda construir sobre 2^256 valores. Lo unico que hace falta es que la huella sea
 * unidireccional, y para eso SHA-256 alcanza. Meterle Argon2 a esto costaria cientos de
 * milisegundos por request de refresh sin agregar seguridad.
 *
 * <p>La comparacion final la hace el indice unico de la base, no este codigo: no hay aca una
 * comparacion de secretos sensible al tiempo.
 */
public final class TokenDigest {

	/** Largo del hash en hexadecimal. Coincide con el {@code CHAR(64)} de las tablas. */
	public static final int LARGO_HEX = 64;

	private TokenDigest() {
		// Clase de utilidad.
	}

	/**
	 * Devuelve el SHA-256 hexadecimal en minusculas del token en claro.
	 *
	 * @param tokenPlano valor tal como viaja en el enlace o en la cookie
	 * @throws IllegalArgumentException si el token es nulo o vacio: buscar por el hash del
	 *         string vacio encontraria filas reales y convertiria un pedido sin token en un
	 *         pedido con un token valido
	 */
	public static String of(String tokenPlano) {
		if (tokenPlano == null || tokenPlano.isBlank()) {
			throw new IllegalArgumentException("No se puede hashear un token vacio");
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(tokenPlano.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			// SHA-256 es obligatorio en toda JVM (JLS / JCA standard names).
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
