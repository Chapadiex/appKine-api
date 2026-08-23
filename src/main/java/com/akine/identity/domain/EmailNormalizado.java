package com.akine.identity.domain;

import java.util.Locale;

/**
 * Forma canonica de una direccion de correo dentro de AKINE.
 *
 * <p>Existe como una sola funcion y en {@code domain} porque de ella depende un invariante:
 * {@code uk_cuenta_email_normalizado} es la materializacion de "una persona, una cuenta"
 * (RF-M02-001). Si el login normalizara distinto que el alta, la misma persona podria crear
 * una segunda cuenta escribiendo su direccion con otra combinacion de mayusculas, y el unique
 * no se enteraria.
 *
 * <p><b>Que hace y que NO hace.</b> Recorta espacios y pasa a minusculas con
 * {@link Locale#ROOT} —nunca con la locale por defecto: en turco {@code "I"} baja a un punto
 * sin punto y la misma direccion normalizaria distinto segun donde corra el servidor—. No
 * quita puntos ni sufijos {@code +etiqueta}: para el estandar la parte local es del servidor
 * de destino, y tratar {@code juan.perez@} y {@code juanperez@} como la misma persona seria
 * una suposicion sobre un proveedor concreto que le impediria registrarse a alguien con una
 * direccion legitimamente distinta.
 */
public final class EmailNormalizado {

	private EmailNormalizado() {
		// Clase de utilidad.
	}

	/**
	 * Devuelve la forma canonica.
	 *
	 * @throws IllegalArgumentException si el email es nulo o esta en blanco
	 */
	public static String of(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("El email es obligatorio");
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}
}
