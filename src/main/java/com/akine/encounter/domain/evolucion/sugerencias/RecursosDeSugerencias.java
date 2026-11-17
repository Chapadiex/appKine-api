package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Carga los recursos versionados de {@code evolucion/} del classpath. */
public final class RecursosDeSugerencias {

	static final String CONSULTAS = "evolucion/consultas-por-hallazgo.properties";

	static final String FORMAS_PRESCRIPTIVAS = "evolucion/formas-prescriptivas.txt";

	private RecursosDeSugerencias() {
	}

	/** La frase de busqueda de un codigo elegible. */
	public static String consultaDe(CodigoDeHallazgo codigo) {
		String frase = cargarConsultas().getProperty(codigo.name());
		if (frase == null) {
			throw new IllegalStateException("Falta la consulta de " + codigo + " en " + CONSULTAS);
		}
		return frase;
	}

	public static List<String> formasPrescriptivas() {
		try (InputStream in = abrir(FORMAS_PRESCRIPTIVAS)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
					.filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Properties cargarConsultas() {
		try (InputStream in = abrir(CONSULTAS);
				InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			Properties p = new Properties();
			p.load(r);
			return p;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static InputStream abrir(String recurso) {
		InputStream in = RecursosDeSugerencias.class.getClassLoader().getResourceAsStream(recurso);
		if (in == null) {
			throw new IllegalStateException("Falta el recurso " + recurso);
		}
		return in;
	}
}
