package com.akine.knowledge.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Marca los pedazos de texto que parecen instrucciones dirigidas a un modelo. */
public final class DetectorDeInstrucciones {

	static final String RECURSO_POR_DEFECTO = "knowledge/frases-sospechosas.txt";

	private static final List<String> MARCADORES = List.of("system:", "[inst]", "<|im_start|>", "<<sys>>");

	private static final int MIN_CARACTERES_POR_PAGINA = 50;

	private final List<String> frases;

	public DetectorDeInstrucciones(List<String> frases) {
		List<String> normalizadas = new ArrayList<>();
		for (String frase : frases) {
			String n = normalizar(frase);
			if (!n.isEmpty()) {
				normalizadas.add(n);
			}
		}
		this.frases = List.copyOf(normalizadas);
	}

	/** Detector con la lista que viaja en el classpath. */
	public static DetectorDeInstrucciones porDefecto() {
		try (InputStream in = DetectorDeInstrucciones.class.getClassLoader().getResourceAsStream(RECURSO_POR_DEFECTO)) {
			if (in == null) {
				throw new IllegalStateException("Falta el recurso " + RECURSO_POR_DEFECTO);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			List<String> lineas = new ArrayList<>();
			String linea;
			while ((linea = reader.readLine()) != null) {
				String t = linea.strip();
				if (!t.isEmpty() && !t.startsWith("#")) {
					lineas.add(t);
				}
			}
			return new DetectorDeInstrucciones(lineas);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public boolean esSospechoso(String texto) {
		if (texto == null || texto.isBlank()) {
			return false;
		}
		String n = normalizar(texto);
		for (String marcador : MARCADORES) {
			if (n.contains(marcador)) {
				return true;
			}
		}
		for (String frase : frases) {
			if (n.contains(frase)) {
				return true;
			}
		}
		return false;
	}

	/** Menos de 50 caracteres no blancos por pagina en promedio (el escaneado). */
	public static boolean casiSinTexto(List<PaginaDeTexto> paginas) {
		if (paginas.isEmpty()) {
			return true;
		}
		long total = 0;
		for (PaginaDeTexto p : paginas) {
			String t = p.texto();
			if (t == null) {
				continue;
			}
			for (int i = 0; i < t.length(); i++) {
				if (!Character.isWhitespace(t.charAt(i))) {
					total++;
				}
			}
		}
		return total < (long) MIN_CARACTERES_POR_PAGINA * paginas.size();
	}

	static String normalizar(String texto) {
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}
}
