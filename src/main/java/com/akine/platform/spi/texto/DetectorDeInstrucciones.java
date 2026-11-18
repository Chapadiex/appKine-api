package com.akine.platform.spi.texto;

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
import java.util.regex.Pattern;

/**
 * Marca los pedazos de texto que parecen instrucciones dirigidas a un modelo. Lo usa todo modulo que
 * le pasa texto de terceros a un modelo de lenguaje.
 */
public final class DetectorDeInstrucciones {

	static final String RECURSO_POR_DEFECTO = "platform/frases-sospechosas.txt";

	/** Rol al principio de linea (texto crudo en minusculas), o tokens que nunca son prosa. */
	private static final Pattern MARCADORES = Pattern
			.compile("(?m)^\\s*(system|assistant|user)\\s*:|\\[inst\\]|<\\|im_start\\|>|<<sys>>");

	private static final Pattern MARCAS_COMBINANTES = Pattern.compile("\\p{M}+");

	private static final Pattern ESPACIOS = Pattern.compile("\\s+");

	private final List<Pattern> frases;

	public DetectorDeInstrucciones(List<String> frases) {
		List<Pattern> patrones = new ArrayList<>();
		for (String frase : frases) {
			String n = normalizar(frase);
			if (!n.isEmpty()) {
				patrones.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(n) + "(?![\\p{L}\\p{N}])"));
			}
		}
		this.frases = List.copyOf(patrones);
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
		if (MARCADORES.matcher(texto.toLowerCase(Locale.ROOT)).find()) {
			return true;
		}
		String n = normalizar(texto);
		for (Pattern frase : frases) {
			if (frase.matcher(n).find()) {
				return true;
			}
		}
		return false;
	}

	static String normalizar(String texto) {
		String sinTildes = MARCAS_COMBINANTES.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
		return ESPACIOS.matcher(sinTildes.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
	}
}
