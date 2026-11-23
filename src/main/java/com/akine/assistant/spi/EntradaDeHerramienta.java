package com.akine.assistant.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Lectura tolerante de la entrada JSON que manda el modelo a una herramienta: los numeros pueden llegar
 * como {@code Integer}, {@code Long}, {@code Double} o texto. Un valor que no se entiende lanza
 * {@link IllegalArgumentException}, que vuelve al modelo como error.
 */
public final class EntradaDeHerramienta {

	private EntradaDeHerramienta() {
	}

	public static String texto(Map<String, Object> entrada, String clave) {
		Object v = entrada.get(clave);
		return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
	}

	public static Long id(Map<String, Object> entrada, String clave) {
		Object v = entrada.get(clave);
		if (v == null || String.valueOf(v).isBlank()) {
			return null;
		}
		try {
			long id = v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v).strip());
			if (id < 1) {
				throw new NumberFormatException();
			}
			return id;
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + clave + "' tiene que ser un id positivo");
		}
	}

	public static BigDecimal monto(Map<String, Object> entrada, String clave) {
		Object v = entrada.get(clave);
		if (v == null || String.valueOf(v).isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(String.valueOf(v).strip().replace(",", "."));
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + clave + "' tiene que ser un numero");
		}
	}

	public static List<String> textos(Map<String, Object> entrada, String clave) {
		Object v = entrada.get(clave);
		if (v instanceof List<?> lista) {
			return lista.stream().filter(e -> e != null && !String.valueOf(e).isBlank())
					.map(e -> String.valueOf(e).strip()).toList();
		}
		return v == null ? List.of() : List.of(String.valueOf(v).strip());
	}
}
