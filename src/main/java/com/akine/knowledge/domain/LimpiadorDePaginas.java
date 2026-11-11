package com.akine.knowledge.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saca del texto de un libro lo que no sirve para responder (M30, ingesta v2).
 *
 * <ol>
 *   <li><b>Quita los encabezados y pies repetidos:</b> un renglon corto (hasta
 *       {@value #MAX_LARGO_DE_BORDE} caracteres) de los tres primeros o los tres ultimos de la
 *       pagina que aparece en mas del 30 % de las paginas (con los numeros igualados, para que
 *       "Modulo 2 · 13" y "Modulo 2 · 14" cuenten como el mismo). Solo en libros de
 *       {@value #MIN_PAGINAS_PARA_REPETIDOS} paginas o mas. Va primero: un pie de editorial
 *       repetido no tiene que contar como marca de derechos en cada pagina.</li>
 *   <li><b>Descarta las paginas de relleno</b> (ver {@link #esRelleno}): derechos y catalogacion,
 *       indice, miembros de los grupos de trabajo y referencias.</li>
 *   <li><b>Une los rotulos verticales partidos</b> de las tablas: cinco o mas pedacitos de 1 a 3
 *       letras seguidos, con al menos dos consonantes sueltas ("A ct iv id ad e s d e la v id a d
 *       ia ri a"). Si la misma frase aparece entera en la pagina, se usa con sus espacios
 *       ("Actividades de la vida diaria"); si no, queda como una sola palabra.</li>
 * </ol>
 *
 * <p><b>Red de seguridad:</b> si la limpieza no deja ninguna pagina o se lleva mas del
 * {@value #MAX_PORCENTAJE_QUITADO} % del texto, devuelve las paginas sin limpiar: una regla que se
 * equivoca con un libro entero no puede dejarlo sin indice.
 *
 * <p>Conserva el numero de cada pagina que queda y sus saltos de renglon.
 */
public final class LimpiadorDePaginas {

	static final int MIN_PAGINAS_PARA_REPETIDOS = 4;
	/** Mas del 30 % de las paginas, en enteros (el dominio no usa punto flotante). */
	private static final int PORCENTAJE_REPETIDO = 30;
	private static final int RENGLONES_DE_BORDE = 3;
	static final int MAX_LARGO_DE_BORDE = 80;
	private static final int MIN_FRAGMENTOS_ROTULO = 5;
	/** Si la limpieza quita mas que este porcentaje del texto, se indexa el libro sin limpiar. */
	static final int MAX_PORCENTAJE_QUITADO = 40;

	private static final Pattern RENGLON = Pattern.compile("\\r?\\n");
	private static final Pattern NUMEROS = Pattern.compile("\\d+");
	private static final Pattern ESPACIOS = Pattern.compile("\\s+");

	private static final List<Pattern> MARCAS_DE_DERECHOS = List.of(
			Pattern.compile("derechos reservados"),
			Pattern.compile("all rights reserved"),
			Pattern.compile("\\bisbn\\b"),
			Pattern.compile("\\(cip\\)|catalogacion"),
			Pattern.compile("notas? de descargo"),
			Pattern.compile("creative commons"),
			Pattern.compile("copyright|©"));

	/** Un renglon de indice: texto y al final, tras espacios o puntos, un numero de pagina. */
	private static final Pattern RENGLON_DE_INDICE = Pattern.compile("^.*\\p{L}.*[\\s.]\\d{1,4}$");
	/** Tabulador, puntos o puntos suspensivos antes del numero: la guia tipica de un indice. */
	private static final Pattern GUIA_DE_INDICE = Pattern.compile(".*(\\t|\\.{2,}|\\u2026)\\s*\\d{1,4}$");
	private static final Pattern TITULO_DE_INDICE = Pattern
			.compile("^(indice|contenido|contenidos|contents|table of contents|sumario)\\b.*");
	private static final Pattern NUMERO_FINAL = Pattern.compile("(\\d{1,4})$");
	/** Una referencia: numero y un apellido seguido de iniciales o de una coma. */
	private static final Pattern RENGLON_DE_REFERENCIA = Pattern
			.compile("^\\d{1,3}[.)]?\\s+\\p{Lu}[\\p{L}'’-]+(\\s+\\p{Lu}{1,3}\\b|,).*");
	private static final Pattern ENTRADA_NUMERADA = Pattern.compile("^\\d{1,3}\\.\\s+\\S.*");
	private static final Pattern TITULO_DE_REFERENCIAS = Pattern
			.compile("^(\\d+(\\.\\d+)*\\.?\\s*)?(referencias|bibliografia|references)\\b.*");
	private static final Pattern TITULO_DE_MIEMBROS = Pattern
			.compile("^(\\d+(\\.\\d+)*\\.?\\s*)?miembros de los grupos de trabajo\\b.*");
	/**
	 * "Carolina CANCELLIERE (epidemiologa clinica, Canada);": apellido en mayusculas, profesion y
	 * pais entre parentesis, y punto y coma. Sin el punto y coma, una lista de siglas ("ACV
	 * (accidente, cerebrovascular)") tambien coincidiria.
	 */
	private static final Pattern MIEMBRO = Pattern
			.compile("\\b\\p{Lu}{3,}[\\p{Lu}'’-]*\\s*\\([^)]*,[^)]*\\)\\s*;");
	private static final Pattern FRAGMENTO = Pattern.compile("\\p{L}{1,3}");
	private static final Pattern CONSONANTE_SUELTA = Pattern.compile("[^aeiouyáéíóú]",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private LimpiadorDePaginas() {
	}

	public static List<PaginaDeTexto> limpiar(List<PaginaDeTexto> paginas) {
		Set<String> repetidos = repetidos(paginas);
		List<PaginaDeTexto> limpias = new ArrayList<>(paginas.size());
		for (PaginaDeTexto p : paginas) {
			String texto = limpiarPagina(p.texto(), repetidos);
			if (!esRelleno(renglones(texto), paginas.size())) {
				limpias.add(new PaginaDeTexto(p.numero(), texto));
			}
		}
		if (limpias.isEmpty()
				|| caracteres(limpias) * 100 < caracteres(paginas) * (100 - MAX_PORCENTAJE_QUITADO)) {
			return paginas;
		}
		return limpias;
	}

	private static long caracteres(List<PaginaDeTexto> paginas) {
		long n = 0;
		for (PaginaDeTexto p : paginas) {
			n += p.texto() == null ? 0 : p.texto().strip().length();
		}
		return n;
	}

	// ---------------------------------------------------------------- relleno

	/**
	 * Pagina de relleno:
	 * <ul>
	 *   <li><b>derechos y catalogacion:</b> dos o mas marcas ("derechos reservados", ISBN, CIP,
	 *       "notas de descargo", Creative Commons, copyright) fuera de los tres primeros y tres
	 *       ultimos renglones; en una pagina de seis renglones o menos, tres marcas;</li>
	 *   <li><b>indice:</b> cinco o mas renglones que terminan en un numero y son al menos la mitad
	 *       de la pagina, y ademas un titulo de indice, o tabuladores o puntos antes del numero en
	 *       la mitad de esos renglones, o numeros que no decrecen y no pasan de
	 *       {@code totalPaginas} (una tabla de dosis tambien termina sus renglones en numeros);</li>
	 *   <li><b>referencias:</b> la mitad de los renglones son "numero + apellido + iniciales", o el
	 *       titulo es "Referencias" y hay tres entradas numeradas;</li>
	 *   <li><b>miembros:</b> el titulo "Miembros de los grupos de trabajo", o cinco entradas
	 *       "APELLIDO (profesion, pais);".</li>
	 * </ul>
	 */
	static boolean esRelleno(List<String> renglones, int totalPaginas) {
		List<String> llenos = renglones.stream().map(String::strip).filter(r -> !r.isEmpty()).toList();
		if (llenos.isEmpty()) {
			return false;
		}
		if (esDeDerechos(llenos)) {
			return true;
		}
		List<String> titulo = llenos.subList(0, Math.min(3, llenos.size())).stream()
				.map(LimpiadorDePaginas::sinTildes).toList();
		if (esIndice(llenos, titulo, totalPaginas)) {
			return true;
		}
		long deReferencia = llenos.stream().filter(r -> RENGLON_DE_REFERENCIA.matcher(r).matches()).count();
		// Con el titulo alcanzan tres entradas numeradas: muchas referencias son de instituciones
		// ("Normas de ortoprotesica de la OMS. Ginebra..."), sin apellido.
		long numeradas = llenos.stream().filter(r -> ENTRADA_NUMERADA.matcher(r).matches()).count();
		if (deReferencia * 2 >= llenos.size()
				|| (numeradas >= 3 && titulo.stream().anyMatch(t -> TITULO_DE_REFERENCIAS.matcher(t).matches()))) {
			return true;
		}
		if (titulo.stream().anyMatch(t -> TITULO_DE_MIEMBROS.matcher(t).matches())) {
			return true;
		}
		Matcher miembros = MIEMBRO.matcher(String.join(" ", llenos));
		int cuantos = 0;
		while (miembros.find() && cuantos < 5) {
			cuantos++;
		}
		return cuantos >= 5;
	}

	private static boolean esDeDerechos(List<String> llenos) {
		boolean corta = llenos.size() <= RENGLONES_DE_BORDE * 2;
		List<String> cuerpo = corta ? llenos
				: llenos.subList(RENGLONES_DE_BORDE, llenos.size() - RENGLONES_DE_BORDE);
		String todo = sinTildes(String.join(" ", cuerpo));
		long marcas = MARCAS_DE_DERECHOS.stream().filter(m -> m.matcher(todo).find()).count();
		return marcas >= (corta ? 3 : 2);
	}

	private static boolean esIndice(List<String> llenos, List<String> titulo, int totalPaginas) {
		List<String> deIndice = llenos.stream().filter(r -> RENGLON_DE_INDICE.matcher(r).matches()).toList();
		if (deIndice.size() < 5 || deIndice.size() * 2 < llenos.size()) {
			return false;
		}
		if (titulo.stream().anyMatch(t -> TITULO_DE_INDICE.matcher(t).matches())) {
			return true;
		}
		long conGuia = deIndice.stream().filter(r -> GUIA_DE_INDICE.matcher(r).matches()).count();
		if (conGuia * 2 >= deIndice.size()) {
			return true;
		}
		int anterior = 0;
		for (String r : deIndice) {
			Matcher m = NUMERO_FINAL.matcher(r);
			if (!m.find()) {
				return false;
			}
			int pagina = Integer.parseInt(m.group(1));
			if (pagina < anterior || pagina > totalPaginas) {
				return false;
			}
			anterior = pagina;
		}
		return true;
	}


	// ---------------------------------------------------------------- encabezados y pies

	private static Set<String> repetidos(List<PaginaDeTexto> paginas) {
		if (paginas.size() < MIN_PAGINAS_PARA_REPETIDOS) {
			return Set.of();
		}
		Map<String, Integer> enCuantas = new HashMap<>();
		for (PaginaDeTexto p : paginas) {
			List<String> renglones = renglones(p.texto());
			Set<String> deEsta = new HashSet<>();
			for (int i : bordes(renglones)) {
				deEsta.add(clave(renglones.get(i)));
			}
			deEsta.forEach(c -> enCuantas.merge(c, 1, Integer::sum));
		}
		Set<String> repetidos = new HashSet<>();
		enCuantas.forEach((clave, n) -> {
			if (n > 1 && n * 100 > paginas.size() * PORCENTAJE_REPETIDO) {
				repetidos.add(clave);
			}
		});
		return repetidos;
	}

	/**
	 * Las posiciones de los tres primeros y los tres ultimos renglones no vacios que son cortos
	 * (hasta {@value #MAX_LARGO_DE_BORDE} caracteres): un encabezado o un pie no es un renglon de
	 * texto corrido.
	 */
	private static Set<Integer> bordes(List<String> renglones) {
		List<Integer> llenos = new ArrayList<>();
		for (int i = 0; i < renglones.size(); i++) {
			if (!renglones.get(i).isBlank()) {
				llenos.add(i);
			}
		}
		Set<Integer> bordes = new HashSet<>();
		for (int k = 0; k < llenos.size(); k++) {
			int i = llenos.get(k);
			boolean enBorde = k < RENGLONES_DE_BORDE || k >= llenos.size() - RENGLONES_DE_BORDE;
			if (enBorde && renglones.get(i).strip().length() <= MAX_LARGO_DE_BORDE) {
				bordes.add(i);
			}
		}
		return bordes;
	}

	private static String clave(String renglon) {
		return NUMEROS.matcher(ESPACIOS.matcher(renglon.strip()).replaceAll(" ")).replaceAll("#")
				.toLowerCase(Locale.ROOT);
	}

	private static String limpiarPagina(String texto, Set<String> repetidos) {
		List<String> renglones = renglones(texto);
		Set<Integer> bordes = repetidos.isEmpty() ? Set.of() : bordes(renglones);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < renglones.size(); i++) {
			String renglon = renglones.get(i);
			if (bordes.contains(i) && repetidos.contains(clave(renglon))) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append('\n');
			}
			sb.append(unirRotulos(renglon, texto));
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- rotulos verticales

	/** Une en {@code renglon} las secuencias de rotulo partido; {@code pagina} da la frase con espacios. */
	static String unirRotulos(String renglon, String pagina) {
		String[] tokens = renglon.strip().split("\\s+");
		if (tokens.length < MIN_FRAGMENTOS_ROTULO) {
			return renglon;
		}
		List<String> salida = new ArrayList<>();
		int i = 0;
		boolean cambio = false;
		while (i < tokens.length) {
			int fin = i;
			while (fin < tokens.length && FRAGMENTO.matcher(tokens[fin]).matches()) {
				fin++;
			}
			if (fin - i >= MIN_FRAGMENTOS_ROTULO && consonantesSueltas(tokens, i, fin) >= 2) {
				StringBuilder junto = new StringBuilder();
				for (int k = i; k < fin; k++) {
					junto.append(tokens[k]);
				}
				salida.add(conEspacios(junto.toString(), fin - i, pagina));
				cambio = true;
				i = fin;
			}
			else {
				salida.add(tokens[i]);
				i++;
			}
		}
		return cambio ? String.join(" ", salida) : renglon;
	}

	private static int consonantesSueltas(String[] tokens, int desde, int hasta) {
		int n = 0;
		for (int k = desde; k < hasta; k++) {
			if (tokens[k].length() == 1 && CONSONANTE_SUELTA.matcher(tokens[k]).matches()) {
				n++;
			}
		}
		return n;
	}

	/**
	 * La misma frase escrita con espacios en la pagina, si esta; si no, la palabra unida. La frase
	 * tiene que tener menos de la mitad de palabras que fragmentos, para no encontrar el propio
	 * rotulo partido.
	 */
	private static String conEspacios(String junto, int fragmentos, String pagina) {
		String buscado = junto.toLowerCase(Locale.ROOT);
		String[] palabras = pagina.strip().split("\\s+");
		for (int i = 0; i < palabras.length; i++) {
			StringBuilder acumulado = new StringBuilder();
			for (int j = i; j < palabras.length && acumulado.length() < buscado.length(); j++) {
				String palabra = palabras[j].replaceAll("[^\\p{L}]", "");
				if (palabra.isEmpty()) {
					break;
				}
				acumulado.append(palabra.toLowerCase(Locale.ROOT));
				if (j > i && (j - i + 1) * 2 < fragmentos && acumulado.toString().equals(buscado)) {
					StringBuilder frase = new StringBuilder();
					for (int k = i; k <= j; k++) {
						if (k > i) {
							frase.append(' ');
						}
						frase.append(palabras[k].replaceAll("[^\\p{L}]", ""));
					}
					return Character.toUpperCase(frase.charAt(0)) + frase.substring(1);
				}
			}
		}
		return junto;
	}

	// ---------------------------------------------------------------- soporte

	private static List<String> renglones(String texto) {
		return texto == null ? List.of() : List.of(RENGLON.split(texto, -1));
	}

	private static String sinTildes(String texto) {
		return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT);
	}
}
