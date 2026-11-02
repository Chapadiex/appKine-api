package com.akine.platform.spi.listado;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

/**
 * El parametro {@code q} de los listados (DP-45, punto 1): busca "contiene", sin distinguir
 * mayusculas ni acentos.
 *
 * <p>En la base lo resuelve la collation {@code utf8mb4_0900_ai_ci} de las tablas —acento y caso
 * insensibles— con el patron de {@link #patronLike}. En memoria lo resuelve {@link #contiene},
 * que pliega los dos lados de la misma forma.
 */
public final class TextoDeBusqueda {

	/** Marcas diacriticas que deja sueltas la descomposicion NFD. */
	private static final String DIACRITICOS = "\\p{M}";

	private TextoDeBusqueda() {
	}

	/** {@code true} si el cliente mando algo para buscar. */
	public static boolean hay(String q) {
		return q != null && !q.isBlank();
	}

	/**
	 * Patron de {@code LIKE} con los comodines puestos por el servidor, o {@code null} si no hay
	 * texto. Los metacaracteres del cliente se escapan: sin eso, un {@code %} tipeado devuelve
	 * todo y un {@code _} hace de comodin. Mismo escape que {@code FinanciadorBusqueda.patron()}.
	 */
	public static String patronLike(String q) {
		if (!hay(q)) {
			return null;
		}
		String escapado = q.strip()
				.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_");
		return "%" + escapado + "%";
	}

	/** Minusculas y sin acentos, para comparar en memoria. {@code null} queda {@code null}. */
	public static String plegar(String valor) {
		if (valor == null) {
			return null;
		}
		return Normalizer.normalize(valor, Normalizer.Form.NFD)
				.replaceAll(DIACRITICOS, "")
				.toLowerCase(Locale.ROOT);
	}

	/** Si {@code valor} contiene {@code q}, sin distinguir mayusculas ni acentos. */
	public static boolean contiene(String valor, String q) {
		if (!hay(q)) {
			return true;
		}
		return valor != null && plegar(valor).contains(plegar(q.strip()));
	}

	/** Si alguno de los valores contiene {@code q}. */
	public static boolean algunoContiene(String q, String... valores) {
		if (!hay(q)) {
			return true;
		}
		for (String valor : valores) {
			if (contiene(valor, q)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Comparador de texto para ordenar en memoria: sin distinguir mayusculas ni acentos, con los
	 * nulos al final.
	 */
	public static <T> Comparator<T> comparadorDeTexto(Function<T, String> extractor) {
		return Comparator.comparing(
				(T fila) -> plegar(extractor.apply(fila)),
				Comparator.nullsLast(Comparator.naturalOrder()));
	}
}
