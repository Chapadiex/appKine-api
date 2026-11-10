package com.akine.knowledge.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Parte el texto de un libro en pedazos de {@code tamano} caracteres con {@code solape} de repeticion
 * entre vecinos, cortando en espacios y conservando el rango de paginas de cada uno.
 */
public final class Troceador {

	private final int tamano;
	private final int solape;
	private final int minimo;

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
		StringBuilder todo = new StringBuilder();
		List<int[]> inicios = new ArrayList<>(); // {offset de inicio, numero de pagina}
		for (PaginaDeTexto p : paginas) {
			String t = p.texto() == null ? "" : p.texto().replaceAll("\\s+", " ").strip();
			if (t.isEmpty()) {
				continue;
			}
			if (!todo.isEmpty()) {
				todo.append(' ');
			}
			inicios.add(new int[] { todo.length(), p.numero() });
			todo.append(t);
		}
		String texto = todo.toString();
		int largo = texto.length();
		List<Pedazo> pedazos = new ArrayList<>();
		int inicio = 0;
		while (inicio < largo) {
			int fin = Math.min(inicio + tamano, largo);
			boolean cortePorPalabra = false;
			if (fin < largo) {
				int espacio = texto.lastIndexOf(' ', fin);
				if (espacio > inicio) {
					fin = espacio;
					cortePorPalabra = true;
				}
				if (largo - fin < minimo) {
					fin = largo; // el resto es chico: se pega a este pedazo
				}
			}
			agregar(pedazos, texto, inicios, inicio, fin);
			if (fin >= largo) {
				break;
			}
			int siguiente;
			if (cortePorPalabra) {
				siguiente = texto.lastIndexOf(' ', Math.max(fin - solape, 0)) + 1;
				if (siguiente <= inicio) {
					siguiente = fin + 1;
				}
			}
			else {
				siguiente = Math.max(fin - solape, inicio + 1);
			}
			inicio = siguiente;
		}
		return pedazos;
	}

	private static void agregar(List<Pedazo> pedazos, String texto, List<int[]> inicios, int desde, int hasta) {
		String parte = texto.substring(desde, hasta).strip();
		if (parte.isEmpty()) {
			return;
		}
		int primero = desde;
		while (primero < hasta && texto.charAt(primero) == ' ') {
			primero++;
		}
		int ultimo = Math.max(hasta - 1, primero);
		pedazos.add(new Pedazo(pedazos.size(), paginaEn(inicios, primero), paginaEn(inicios, ultimo), parte));
	}

	private static int paginaEn(List<int[]> inicios, int offset) {
		int pagina = inicios.isEmpty() ? 0 : inicios.get(0)[1];
		for (int[] i : inicios) {
			if (i[0] > offset) {
				break;
			}
			pagina = i[1];
		}
		return pagina;
	}
}
