package com.akine.assistant.application;

import com.akine.platform.infrastructure.modelo.OllamaModeloDeLenguaje;
import com.akine.assistant.spi.Intencion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mide el interpretador real —prompt, validacion, alias, herencia y resolutor— contra un Ollama
 * real, con los casos de {@code docs/asistente/evaluacion/}. <b>No corre en el CI</b>: solo con
 * {@code AKINE_OLLAMA_URL} definida (por ejemplo {@code http://localhost:11434}).
 *
 * <p>Compara lo que importa al final: la intencion, la persona y la clase normalizadas, el reporte y
 * <b>los dias</b> que resultan del periodo, con el "hoy" fijo del 15/11/2026 en que se escribieron los
 * casos. Asi una fecha que el modelo escribe con otro anio cuenta como acierto si el resolutor la
 * corrige, que es exactamente lo que pasa en produccion.
 */
@EnabledIfEnvironmentVariable(named = "AKINE_OLLAMA_URL", matches = "https?://.+")
class InterpretadorContraOllamaTest {

	private static final LocalDate HOY = LocalDate.of(2026, 11, 15);
	private static final double MINIMO_INTENCION = 0.90;

	@Test
	void interpreta_los_casos_medidos() throws IOException {
		String prompt = Files.readString(Path.of("src/main/resources/asistente/prompt-interpretador.txt"), StandardCharsets.UTF_8);
		Interpretador interpretador = new Interpretador(
				() -> new OllamaModeloDeLenguaje(System.getenv("AKINE_OLLAMA_URL"),
						Objects.requireNonNullElse(System.getenv("AKINE_OLLAMA_MODELO"), "qwen3:4b-instruct"), "2h"),
				prompt, Duration.ofSeconds(30));

		int total = 0;
		int completos = 0;
		int intenciones = 0;
		List<String> fallas = new ArrayList<>();
		for (String archivo : archivos()) {
			for (JsonNode caso : JsonMapper.builder().build().readTree(Path.of("docs/asistente/evaluacion", archivo).toFile())) {
				total++;
				Interpretacion obtenida = interpretador.interpretar(caso.path("q").asString(), historial(caso));
				JsonNode e = caso.path("e");
				String q = caso.path("q").asString();
				Intencion esperada = Intencion.valueOf(e.path("intencion").asString());
				boolean intencionOk = obtenida.intencion() == esperada;
				boolean ok = intencionOk
						&& igual(e, "persona", obtenida.persona())
						&& igual(e, "clase", obtenida.clase())
						&& (!e.has("reporte") || Objects.equals(texto(e, "reporte"), obtenida.reporte()))
						&& (!e.has("periodo") || mismosDias(texto(e, "periodo"), obtenida.periodo(), q, esperada));
				intenciones += intencionOk ? 1 : 0;
				completos += ok ? 1 : 0;
				if (!ok) {
					fallas.add(caso.path("id").asString() + " " + q + " -> " + obtenida);
				}
			}
		}
		System.out.printf("Interpretador contra Ollama: intencion %d/%d, completos %d/%d%n", intenciones, total, completos, total);
		fallas.forEach(f -> System.out.println("  FALLA " + f));

		assertThat((double) intenciones / total).isGreaterThanOrEqualTo(MINIMO_INTENCION);
	}

	/** Por defecto los tres sets; con AKINE_EVALUACION_SET se mide uno solo (por ejemplo preguntas-limpias.json). */
	private static List<String> archivos() {
		String uno = System.getenv("AKINE_EVALUACION_SET");
		return uno == null || uno.isBlank() ? List.of("preguntas.json", "preguntas-control.json", "preguntas-limpias.json", "preguntas-futuro.json") : List.of(uno);
	}

	private static List<TurnoPrevio> historial(JsonNode caso) {
		List<TurnoPrevio> previos = new ArrayList<>();
		for (JsonNode par : caso.path("h")) {
			JsonNode i = par.get(1);
			previos.add(new TurnoPrevio(par.get(0).asString(), new Interpretacion(
					Intencion.valueOf(i.path("intencion").asString()),
					texto(i, "persona"), texto(i, "clase"), texto(i, "periodo"), texto(i, "reporte"))));
		}
		return previos;
	}

	private static boolean igual(JsonNode e, String campo, String obtenido) {
		return !e.has(campo) || normal(texto(e, campo)).equals(normal(obtenido));
	}

	private static boolean mismosDias(String esperado, String obtenido, String pregunta, Intencion intencion) {
		boolean adelante = intencion == Intencion.AGENDA_DEL_DIA;
		Optional<RangoDeDias> a = ResolutorDePeriodo.resolver(ResolutorDePeriodo.normalizar(esperado), pregunta, HOY, adelante);
		Optional<RangoDeDias> b = ResolutorDePeriodo.resolver(obtenido, pregunta, HOY, adelante);
		return a.equals(b);
	}

	private static String texto(JsonNode nodo, String campo) {
		JsonNode v = nodo.path(campo);
		return v.isNull() || v.isMissingNode() ? null : v.asString();
	}

	private static String normal(String s) {
		if (s == null) {
			return "";
		}
		return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
	}
}
