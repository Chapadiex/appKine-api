package com.akine.assistant.application;

import com.akine.assistant.spi.Intencion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuacionDeLaCharlaTest {

	@ParameterizedTest
	@ValueSource(strings = {"tiene turno?", "¿vino?", "¿cuánto debe?", "¿tiene turno mañana?", "y su cobertura?"})
	void sinSujetoEsContinuacion(String pregunta) {
		assertThat(AsistenteService.esContinuacion(pregunta)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"¿quiénes vienen hoy?", "¿cuántos pacientes tenemos?", "¿vino Lucía?",
			"¿la caja está abierta?", "turnos del viernes", "¿qué profesional tiene más turnos este mes?"})
	void conSujetoPropioNoEsContinuacion(String pregunta) {
		assertThat(AsistenteService.esContinuacion(pregunta)).isFalse();
	}

	@Test
	void personasDistintasEnLasFilas() {
		List<Map<String, Object>> filas = List.of(
				Map.of("nombre", "Lucía", "apellido", "Díaz", "inicio", "a"),
				Map.of("nombre", "Lucía", "apellido", "Díaz", "inicio", "b"),
				Map.of("nombre", "Lucía", "apellido", "Pérez", "inicio", "c"),
				Map.of("cantidad", 3));
		assertThat(AsistenteService.personasEn(filas)).containsExactly("Lucía Díaz", "Lucía Pérez");
	}

	@Test
	void laPersonaDeUnaConsultaLibreSeHereda() {
		Interpretacion anterior = new Interpretacion(Intencion.FUERA_DE_ALCANCE, "Lautaro Acosta", null, null, null);
		Interpretacion actual = new Interpretacion(Intencion.ASISTENCIA_PERSONA, null, null, "ESTA_SEMANA", null);
		assertThat(Interpretador.heredar(actual, anterior, false).persona()).isEqualTo("Lautaro Acosta");
	}

	@Test
	void laPreguntaLlevaLasAnterioresYLaPersona() {
		String texto = ConsultaLibre.conAnteriores("¿tiene turno?", List.of("a", "b", "c", "Acosta"),
				"Lautaro Acosta");
		assertThat(texto).doesNotContain("- a\n")
				.contains("- Acosta\n", "Persona de la que se habla: Lautaro Acosta", "Pregunta actual: ¿tiene turno?");
	}
}
