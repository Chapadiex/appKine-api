package com.akine.billing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 en hexadecimal del texto canonico de un pedido, para distinguir un reintento de un reuso
 * de la clave de idempotencia con otro contenido. Es la columna {@code request_hash}.
 *
 * <p>El texto canonico lo arma cada comando; esta clase solo lo resume. La clave nunca entra en el
 * texto: lo que se compara es el contenido.
 */
final class Huella {

	private Huella() {
	}

	static String de(String textoCanonico) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(textoCanonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
