package com.akine.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Hash canonico del alta self-service: SHA-256 hex de los campos del pedido normalizados
 * ({@code strip} + minusculas, ausente = vacio), separados por el caracter de control US.
 * Mismo criterio que el alta de sede de {@code organization}.
 *
 * <p><b>La contrasena no entra.</b> Un SHA-256 sin sal de un string cuyos demas campos estan en
 * la misma fila es un hash de contrasena que se crackea offline, y {@code onboarding_registro}
 * no es donde se guardan credenciales. La consecuencia es aceptada: reintentar con la misma
 * clave y SOLO otra contrasena se trata como replay —no se crea nada y se responde el mismo
 * 202—, que es lo que pasaba antes con cualquier cambio.
 */
public final class HuellaDeRegistro {

	private static final String SEPARADOR = String.valueOf((char) 0x1F);

	private HuellaDeRegistro() {
	}

	public static String de(
			String email,
			String nombre,
			String apellido,
			String organizacionNombre,
			String organizacionSlug,
			String consultorioNombre,
			String planCode) {

		String canonico = String.join(SEPARADOR,
				normalizar(email),
				normalizar(nombre),
				normalizar(apellido),
				normalizar(organizacionNombre),
				normalizar(organizacionSlug),
				normalizar(consultorioNombre),
				normalizar(planCode));
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(canonico.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException imposible) {
			// Obligatorio en toda JVM. Seguir sin hash apagaria en silencio la deteccion.
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}

	private static String normalizar(String valor) {
		return valor == null ? "" : valor.strip().toLowerCase(Locale.ROOT);
	}
}
