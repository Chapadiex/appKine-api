package com.akine.encounter.domain.evolucion.sugerencias;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae y normaliza los numeros de un texto. Regla simple: {@code ,} y {@code .} son separadores
 * decimales (no hay separador de miles), asi que {@code 6,5} == {@code 6.5} y {@code 6} == {@code 6.0}.
 * Un numero pegado a una letra por la izquierda ({@code P2}) es un identificador, no una cantidad, y se
 * ignora.
 */
final class NumerosEnTexto {

	private static final Pattern NUMERO = Pattern.compile("(?<![\\p{L}\\d.,])\\d+(?:[.,]\\d+)?");

	private NumerosEnTexto() {
	}

	static Set<String> extraer(String texto) {
		Set<String> numeros = new LinkedHashSet<>();
		if (texto == null) {
			return numeros;
		}
		Matcher m = NUMERO.matcher(texto);
		while (m.find()) {
			numeros.add(normalizar(m.group()));
		}
		return numeros;
	}

	static String normalizar(String numero) {
		BigDecimal n = new BigDecimal(numero.replace(',', '.'));
		return n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString();
	}
}
