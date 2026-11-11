package com.akine.knowledge.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parte el texto de un libro en pedazos de hasta {@code tamano} caracteres con hasta
 * {@code solape} de repeticion entre vecinos, conservando el rango de paginas de cada uno.
 *
 * <p>Corta en el limite mas fuerte que encuentra en la segunda mitad del pedazo, en este orden:
 * parrafo (renglon en blanco), fin de renglon que cierra una oracion, fin de oracion, fin de
 * renglon (la fila de una tabla) y, solo si no hay nada de eso, entre palabras. Nunca parte una
 * palabra, salvo una sola palabra mas larga que el pedazo. El solape arranca, si puede, despues
 * de un renglon o una oracion. Si lo que queda despues de un corte es menor que {@code minimo},
 * se pega al pedazo anterior.
 */
public final class Troceador {

	private static final Pattern ESPACIOS = Pattern.compile("\\s+");
	private static final Pattern RENGLON = Pattern.compile("\\r?\\n");

	// Fuerza del limite que sigue a una palabra.
	private static final int PALABRA = 0;
	private static final int RENGLON_SUELTO = 1;
	private static final int ORACION = 2;
	private static final int RENGLON_Y_ORACION = 3;
	private static final int PARRAFO = 4;

	private final int tamano;
	private final int solape;
	private final int minimo;

	/** Una palabra, su pagina y la fuerza del limite que la sigue. */
	private record Palabra(String texto, int pagina, int limite) {
	}

	public Troceador(int tamano, int solape, int minimo) {
		if (tamano <= 0) {
			throw new IllegalArgumentException("tamano debe ser positivo");
		}
		if (solape < 0 || solape >= tamano) {
			throw new IllegalArgumentException("solape debe estar entre 0 y tamano - 1");
		}
		this.tamano = tamano;
		this.solape = solape;
		this.minimo = Math.max(minimo, 0);
	}

	public List<Pedazo> trocear(List<PaginaDeTexto> paginas) {
		List<Palabra> palabras = palabras(paginas);
		int n = palabras.size();
		// largo[i] = caracteres de las palabras 0..i-1 unidas por un espacio, mas ese espacio.
		long[] acumulado = new long[n + 1];
		for (int i = 0; i < n; i++) {
			acumulado[i + 1] = acumulado[i] + palabras.get(i).texto().length() + 1;
		}
		List<Pedazo> pedazos = new ArrayList<>();
		int inicio = 0;
		while (inicio < n) {
			Palabra primera = palabras.get(inicio);
			if (primera.texto().length() > tamano) {
				// Una sola palabra mas larga que el pedazo: se corta dura, sin solape.
				cortarDura(pedazos, primera);
				inicio++;
				continue;
			}
			int ultima = inicio;
			while (ultima + 1 < n && largo(acumulado, inicio, ultima + 1) <= tamano) {
				ultima++;
			}
			int corte = ultima;
			if (ultima + 1 < n) {
				corte = mejorCorte(palabras, acumulado, inicio, ultima);
				if (largo(acumulado, corte + 1, n - 1) < minimo) {
					corte = n - 1; // el resto es chico: se pega a este pedazo
				}
			}
			agregar(pedazos, palabras, inicio, corte);
			if (corte >= n - 1) {
				break;
			}
			inicio = inicioDelSolape(palabras, acumulado, inicio, corte);
		}
		return pedazos;
	}

	/** El corte mas fuerte (y el mas tardio entre los iguales) que deja el pedazo al menos a la mitad. */
	private int mejorCorte(List<Palabra> palabras, long[] acumulado, int inicio, int ultima) {
		int mejor = ultima;
		int fuerza = -1;
		for (int i = ultima; i >= inicio; i--) {
			if (largo(acumulado, inicio, i) * 2 < tamano && fuerza >= 0) {
				break;
			}
			int limite = palabras.get(i).limite();
			if (limite > fuerza) {
				fuerza = limite;
				mejor = i;
			}
		}
		return mejor;
	}

	/**
	 * La primera palabra del solape: la mas temprana que entra en {@code solape} caracteres y
	 * viene despues de un renglon u oracion; si ninguna, la mas temprana que entra.
	 */
	private int inicioDelSolape(List<Palabra> palabras, long[] acumulado, int inicio, int corte) {
		int desde = corte + 1;
		while (desde - 1 > inicio && largo(acumulado, desde - 1, corte) <= solape) {
			desde--;
		}
		if (desde > corte) {
			return corte + 1;
		}
		for (int i = desde; i <= corte; i++) {
			if (palabras.get(i - 1).limite() >= RENGLON_SUELTO) {
				return i;
			}
		}
		return desde;
	}

	private static long largo(long[] acumulado, int desde, int hasta) {
		return hasta < desde ? 0 : acumulado[hasta + 1] - acumulado[desde] - 1;
	}

	private void cortarDura(List<Pedazo> pedazos, Palabra palabra) {
		String t = palabra.texto();
		for (int i = 0; i < t.length(); i += tamano) {
			pedazos.add(new Pedazo(pedazos.size(), palabra.pagina(), palabra.pagina(),
					t.substring(i, Math.min(t.length(), i + tamano))));
		}
	}

	private static void agregar(List<Pedazo> pedazos, List<Palabra> palabras, int desde, int hasta) {
		StringBuilder sb = new StringBuilder();
		for (int i = desde; i <= hasta; i++) {
			if (i > desde) {
				sb.append(' ');
			}
			sb.append(palabras.get(i).texto());
		}
		pedazos.add(new Pedazo(pedazos.size(), palabras.get(desde).pagina(), palabras.get(hasta).pagina(),
				sb.toString()));
	}

	/** Las palabras de todas las paginas, con el limite que sigue a cada una. */
	private static List<Palabra> palabras(List<PaginaDeTexto> paginas) {
		List<Palabra> palabras = new ArrayList<>();
		for (PaginaDeTexto p : paginas) {
			if (p.texto() == null) {
				continue;
			}
			boolean renglonEnBlanco = false;
			for (String renglon : RENGLON.split(p.texto(), -1)) {
				String limpio = renglon.strip();
				if (limpio.isEmpty()) {
					renglonEnBlanco = true;
					continue;
				}
				if (renglonEnBlanco) {
					marcarFinDeParrafo(palabras);
					renglonEnBlanco = false;
				}
				String[] partes = ESPACIOS.split(limpio);
				for (int i = 0; i < partes.length; i++) {
					String palabra = partes[i];
					boolean cierraOracion = cierraOracion(palabra);
					int limite;
					if (i == partes.length - 1) {
						limite = cierraOracion ? RENGLON_Y_ORACION : RENGLON_SUELTO;
					}
					else {
						limite = cierraOracion ? ORACION : PALABRA;
					}
					palabras.add(new Palabra(palabra, p.numero(), limite));
				}
			}
		}
		return palabras;
	}

	private static void marcarFinDeParrafo(List<Palabra> palabras) {
		if (!palabras.isEmpty()) {
			Palabra ultima = palabras.get(palabras.size() - 1);
			palabras.set(palabras.size() - 1, new Palabra(ultima.texto(), ultima.pagina(), PARRAFO));
		}
	}

	private static boolean cierraOracion(String palabra) {
		char c = palabra.charAt(palabra.length() - 1);
		return c == '.' || c == '!' || c == '?' || c == ':' || c == ';';
	}
}
