package com.akine.encounter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo.Severidad;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** RF-M31-002: cada alerta tiene texto, con coma decimal y sin indicar conducta. */
class TextosDeHallazgoTest {

	private static final Map<String, String> VALORES = Map.ofEntries(
			Map.entry("presentes", "2"), Map.entry("minimo", "3"),
			Map.entry("desde", "12.5"), Map.entry("hasta", "10.25"), Map.entry("sesiones", "4"),
			Map.entry("valor", "PEOR"), Map.entry("seguidas", "2"),
			Map.entry("medicion", "ROM"), Map.entry("lateralidad", "IZQUIERDA"), Map.entry("unidad", "grados"),
			Map.entry("inasistencias", "2"), Map.entry("citas", "6"), Map.entry("porcentaje", "33"),
			Map.entry("real", "0.5"), Map.entry("planificada", "3"), Map.entry("semanas", "8"),
			Map.entry("semanasTranscurridas", "10"), Map.entry("conducta", "DERIVA"),
			Map.entry("fecha", "2026-11-01"), Map.entry("despues", "2"));

	@Test
	void todasLasAlertasTienenTextoConComaDecimal() {
		Map<CodigoDeHallazgo, String> textos = new EnumMap<>(CodigoDeHallazgo.class);
		for (CodigoDeHallazgo codigo : CodigoDeHallazgo.values()) {
			textos.put(codigo, TextosDeHallazgo.de(hallazgo(codigo, VALORES)));
		}

		assertThat(textos.values()).allSatisfy(texto -> assertThat(texto).isNotBlank().doesNotContain("null"));
		assertThat(textos.get(CodigoDeHallazgo.DOLOR_SIN_MEJORA)).contains("12,5").contains("10,25");
		assertThat(textos.get(CodigoDeHallazgo.MEDICION_EMPEORA)).contains("ROM (izquierda)").contains("grados");
		assertThat(textos.get(CodigoDeHallazgo.FRECUENCIA_BAJO_PLAN)).contains("0,5");
		assertThat(textos.get(CodigoDeHallazgo.EVOLUCION_DESFAVORABLE)).contains("peor");
		assertThat(textos.get(CodigoDeHallazgo.CONDUCTA_SIN_SEGUIR)).contains("derivación");
	}

	@Test
	void variantesDeLadoConductaYEvolucion() {
		Map<String, String> otros = new java.util.HashMap<>(VALORES);
		otros.put("lateralidad", "DERECHA");
		otros.put("valor", "IGUAL");
		otros.put("conducta", "ALTA");
		otros.put("unidad", "");
		assertThat(TextosDeHallazgo.de(hallazgo(CodigoDeHallazgo.MEDICION_SIN_CAMBIO, otros)))
				.contains("ROM (derecha)").doesNotContain("grados");
		assertThat(TextosDeHallazgo.de(hallazgo(CodigoDeHallazgo.EVOLUCION_DESFAVORABLE, otros))).contains("igual");
		assertThat(TextosDeHallazgo.de(hallazgo(CodigoDeHallazgo.CONDUCTA_SIN_SEGUIR, otros))).contains("alta");

		otros.put("lateralidad", "NO_APLICA");
		otros.put("conducta", "REEVALUA");
		assertThat(TextosDeHallazgo.de(hallazgo(CodigoDeHallazgo.MEDICION_SIN_CAMBIO, otros)))
				.startsWith("ROM no varió");
		assertThat(TextosDeHallazgo.de(hallazgo(CodigoDeHallazgo.CONDUCTA_SIN_SEGUIR, otros))).contains("reevaluar");
	}

	private static Hallazgo hallazgo(CodigoDeHallazgo codigo, Map<String, String> valores) {
		return new Hallazgo(codigo, Severidad.ATENCION, List.of(), List.of(), valores);
	}
}
