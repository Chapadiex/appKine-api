package com.akine.encounter.domain.evolucion.sugerencias;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae y normaliza los numeros de un texto.
 *
 * <p>Reglas: se quitan solo los identificadores de cita exactos ({@code P1}..{@code P99}); todo lo demas se
 * extrae aunque este pegado a una letra ({@code 3x10}, {@code EVA7}). Un token es la secuencia completa
 * {@code \d+([.,]\d+)*}. Si tiene un solo separador y no tiene exactamente tres digitos despues, es decimal y
 * se normaliza ({@code 6,5} == {@code 6.5}, {@code 6} == {@code 6.0}). Cualquier otro token ({@code 1.000},
 * {@code 1,000,000}, {@code 6.5.40}) se compara literalmente: nunca se reduce a un numero chico de la lista
 * blanca.
 */
final class NumerosEnTexto {

	private static final Pattern CITA = Pattern.compile("\\bP\\d{1,2}\\b");

	private static final Pattern TOKEN = Pattern.compile("\\d+(?:[.,]\\d+)*");

	private static final Pattern DECIMAL = Pattern.compile("\\d+(?:[.,]\\d{1,2}|[.,]\\d{4,})?");

	private NumerosEnTexto() {
	}

	/** Para lo que enviamos nosotros: quita cualquier identificador de cita con forma de P1..P99. */
	static Set<String> extraer(String texto) {
		return extraer(texto, CITA);
	}

	/** Para la salida del modelo: quita solo los identificadores de cita que de verdad se enviaron. */
	static Set<String> extraer(String texto, Set<String> idsEnviados) {
		if (idsEnviados.isEmpty()) {
			return extraer(texto, (Pattern) null);
		}
		return extraer(texto, Pattern.compile("\\b(?:" + idsEnviados.stream().map(Pattern::quote)
				.collect(java.util.stream.Collectors.joining("|")) + ")\\b"));
	}

	private static Set<String> extraer(String texto, Pattern citas) {
		Set<String> numeros = new LinkedHashSet<>();
		if (texto == null) {
			return numeros;
		}
		Matcher m = TOKEN.matcher(citas == null ? texto : citas.matcher(texto).replaceAll(" "));
		while (m.find()) {
			numeros.add(canonico(m.group()));
		}
		return numeros;
	}

	/** Forma comparable de un token numerico (ver las reglas de la clase). */
	static String canonico(String token) {
		if (!DECIMAL.matcher(token).matches()) {
			return token;
		}
		BigDecimal n = new BigDecimal(token.replace(',', '.'));
		return n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString();
	}
}
