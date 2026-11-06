package com.akine.organization.domain;

import java.util.regex.Pattern;

/**
 * Validacion de CUIT/CUIL argentino (AFIP): once digitos, con guiones opcionales, y un digito
 * verificador modulo 11.
 *
 * <h2>Cuando se aplica</h2>
 *
 * <p>La identificacion fiscal es texto libre: un centro fuera de Argentina carga su RUT o su NIF
 * y nadie puede validarselo aca. La regla es una sola y no depende del pais declarado: <b>si el
 * valor PARECE un CUIT</b> —{@link #pareceCuit}: dos digitos, ocho digitos y uno, con o sin
 * guiones— <b>entonces su digito verificador tiene que cerrar</b>. Lo que no tiene esa forma se
 * acepta tal cual. Asi un tipeo de un digito en un CUIT real se rechaza con 400 y un identificador
 * extranjero nunca choca contra una regla que no le corresponde.
 *
 * <h2>El algoritmo</h2>
 *
 * <p>Pesos {@code 5 4 3 2 7 6 5 4 3 2} sobre los diez primeros digitos; {@code resto = suma mod 11};
 * verificador {@code 11 - resto}, donde {@code 11} se lee {@code 0} y {@code 10} no existe: un CUIT
 * cuyo calculo da 10 no es un CUIT valido (AFIP cambia el prefijo en ese caso).
 */
public final class Cuit {

	private static final Pattern FORMA_DE_CUIT = Pattern.compile("^\\d{2}-?\\d{8}-?\\d$");

	private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

	private Cuit() {
		// Utilidad.
	}

	/** {@code true} si el valor tiene la forma de un CUIT (11 digitos, guiones opcionales). */
	public static boolean pareceCuit(String valor) {
		return valor != null && FORMA_DE_CUIT.matcher(valor.strip()).matches();
	}

	/** Solo los digitos, sin guiones ni espacios. {@code null} para {@code null}. */
	public static String normalizar(String valor) {
		return valor == null ? null : valor.replaceAll("[^0-9]", "");
	}

	/**
	 * {@code true} si el valor es un CUIT bien formado y su digito verificador cierra.
	 *
	 * <p>Un valor que no tiene forma de CUIT es {@code false}: quien quiera "no parece un CUIT, lo
	 * acepto como texto" tiene que preguntar {@link #pareceCuit} antes.
	 */
	public static boolean esValido(String valor) {
		if (!pareceCuit(valor)) {
			return false;
		}
		String digitos = normalizar(valor);
		int suma = 0;
		for (int i = 0; i < PESOS.length; i++) {
			suma += (digitos.charAt(i) - '0') * PESOS[i];
		}
		int verificador = 11 - (suma % 11);
		if (verificador == 11) {
			verificador = 0;
		}
		if (verificador == 10) {
			return false;
		}
		return verificador == digitos.charAt(10) - '0';
	}
}
