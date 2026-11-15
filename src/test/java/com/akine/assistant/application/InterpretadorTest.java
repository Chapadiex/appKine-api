package com.akine.assistant.application;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.MensajeChat.Rol;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import com.akine.assistant.spi.Intencion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterpretadorTest {

	private final List<List<MensajeChat>> llamadas = new ArrayList<>();

	private Interpretador conRespuesta(String json) {
		ModeloDeLenguaje modelo = (mensajes, plazo) -> {
			llamadas.add(mensajes);
			return json;
		};
		return new Interpretador(() -> modelo, "PROMPT", Duration.ofSeconds(5));
	}

	private static String json(String intencion, String persona, String clase, String periodo, String reporte) {
		return "{\"intencion\":" + q(intencion) + ",\"persona\":" + q(persona) + ",\"clase\":" + q(clase)
				+ ",\"periodo\":" + q(periodo) + ",\"reporte\":" + q(reporte) + "}";
	}

	private static String q(String v) {
		return v == null ? "null" : "\"" + v + "\"";
	}

	@Test
	void lee_la_intencion_y_limpia_los_campos() {
		Interpretacion i = conRespuesta(json("ASISTENCIA_PERSONA", "el señor Rodríguez", null, "dia:lunes", null))
				.interpretar("vino el señor Rodríguez el lunes?", List.of());

		assertThat(i).isEqualTo(new Interpretacion(Intencion.ASISTENCIA_PERSONA, "Rodríguez", null, "DIA:LUNES", null));
	}

	@Test
	void los_campos_que_la_intencion_no_usa_se_descartan() {
		Interpretacion i = conRespuesta(json("AGENDA_DEL_DIA", "Juan", "yoga", "HOY", "ECONOMICO"))
				.interpretar("agenda de hoy", List.of());

		assertThat(i).isEqualTo(new Interpretacion(Intencion.AGENDA_DEL_DIA, null, null, "HOY", null));
	}

	@Test
	void la_clase_va_sin_la_palabra_clase() {
		Interpretacion i = conRespuesta(json("ASISTENTES_CLASE", null, "Clase de Yoga", null, null))
				.interpretar("quienes vinieron a yoga", List.of());

		assertThat(i.clase()).isEqualTo("yoga");
	}

	@Test
	void un_reporte_se_normaliza_y_sin_reporte_valido_es_fuera_de_alcance() {
		assertThat(conRespuesta(json("REPORTE", null, null, "MES:PASADO", "económico"))
				.interpretar("cuanto se facturo el mes pasado", List.of()))
				.isEqualTo(new Interpretacion(Intencion.REPORTE, null, null, "MES_PASADO", "ECONOMICO"));
		assertThat(conRespuesta(json("REPORTE", null, null, null, "FACTURACION"))
				.interpretar("cuanto se facturo", List.of()))
				.isEqualTo(Interpretacion.FUERA_DE_ALCANCE);
	}

	@Test
	void un_periodo_ilegible_no_tumba_la_interpretacion() {
		Interpretacion i = conRespuesta(json("SESION_DE_PERSONA", "María", null, "LA_QUINCENA", null))
				.interpretar("se cargo la sesion de maria esta quincena", List.of());

		assertThat(i).isEqualTo(new Interpretacion(Intencion.SESION_DE_PERSONA, "María", null, null, null));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"no es json",
			"[1,2,3]",
			"{\"intencion\":\"ADMIN\",\"persona\":\"todos\"}",
			"{\"intencion\":\"BUSCAR_PERSONA\",\"persona\":\"juan\"}",
			"{\"intencion\":{\"anidado\":true}}",
			"{}"})
	void lo_que_no_es_del_catalogo_es_fuera_de_alcance(String crudo) {
		assertThat(conRespuesta(crudo).interpretar("mostrame todo", List.of()))
				.isEqualTo(Interpretacion.FUERA_DE_ALCANCE);
	}

	@Test
	void una_pregunta_vacia_o_demasiado_larga_no_llama_al_modelo() {
		Interpretador interpretador = conRespuesta(json("AGENDA_DEL_DIA", null, null, "HOY", null));

		assertThat(interpretador.interpretar("   ", List.of())).isEqualTo(Interpretacion.FUERA_DE_ALCANCE);
		assertThat(interpretador.interpretar("a".repeat(Interpretador.MAX_LARGO_PREGUNTA + 1), List.of()))
				.isEqualTo(Interpretacion.FUERA_DE_ALCANCE);
		assertThat(llamadas).isEmpty();
	}

	@Test
	void el_historial_viaja_como_preguntas_y_respuestas_y_solo_los_ultimos_tres() {
		Interpretacion juan = new Interpretacion(Intencion.ASISTENCIA_PERSONA, "Juan", null, "ESTA_SEMANA", null);
		List<TurnoPrevio> historial = List.of(
				new TurnoPrevio("p1", juan), new TurnoPrevio("p2", juan),
				new TurnoPrevio("p3", juan), new TurnoPrevio("p4", juan));

		conRespuesta(json("ASISTENCIA_PERSONA", "Juan", null, "SEMANA_PASADA", null))
				.interpretar("¿y la semana pasada?", historial);

		List<MensajeChat> mensajes = llamadas.getFirst();
		assertThat(mensajes).hasSize(1 + 2 * Interpretador.MAX_TURNOS_PREVIOS + 1);
		assertThat(mensajes.getFirst()).isEqualTo(new MensajeChat(Rol.SISTEMA, "PROMPT"));
		assertThat(mensajes.get(1)).isEqualTo(new MensajeChat(Rol.USUARIO, "p2"));
		assertThat(mensajes.get(2).rol()).isEqualTo(Rol.ASISTENTE);
		assertThat(mensajes.get(2).texto())
				.isEqualTo("{\"intencion\":\"ASISTENCIA_PERSONA\",\"persona\":\"Juan\",\"clase\":null,"
						+ "\"periodo\":\"ESTA_SEMANA\",\"reporte\":null}");
		assertThat(mensajes.getLast()).isEqualTo(new MensajeChat(Rol.USUARIO, "¿y la semana pasada?"));
	}

	@Test
	void en_un_seguimiento_hereda_lo_que_falta_y_la_intencion_usa() {
		// El caso H05 de la medicion del 15/11: el modelo perdia el periodo.
		Interpretacion anterior = new Interpretacion(Intencion.ASISTENCIA_PERSONA, "Juan", null, "ESTA_SEMANA", null);

		Interpretacion i = conRespuesta(json("SESION_DE_PERSONA", null, null, null, null))
				.interpretar("¿y tiene la sesion cargada?", List.of(new TurnoPrevio("Juan vino esta semana?", anterior)));

		assertThat(i).isEqualTo(new Interpretacion(Intencion.SESION_DE_PERSONA, "Juan", null, "ESTA_SEMANA", null));
	}

	@Test
	void no_hereda_a_una_intencion_que_no_usa_el_campo_ni_desde_fuera_de_alcance() {
		Interpretacion juan = new Interpretacion(Intencion.ASISTENCIA_PERSONA, "Juan", null, "AYER", null);
		assertThat(conRespuesta(json("AGENDA_DEL_DIA", null, null, "HOY", null))
				.interpretar("quienes vienen hoy", List.of(new TurnoPrevio("juan vino ayer?", juan))))
				.isEqualTo(new Interpretacion(Intencion.AGENDA_DEL_DIA, null, null, "HOY", null));

		assertThat(conRespuesta(json("SESION_DE_PERSONA", null, null, null, null))
				.interpretar("y la sesion?", List.of(new TurnoPrevio("hola", Interpretacion.FUERA_DE_ALCANCE))))
				.isEqualTo(new Interpretacion(Intencion.SESION_DE_PERSONA, null, null, null, null));
	}

	@Test
	void si_el_modelo_no_responde_no_se_inventa_una_interpretacion() {
		AtomicInteger intentos = new AtomicInteger();
		ModeloDeLenguaje caido = (mensajes, plazo) -> {
			intentos.incrementAndGet();
			throw new ModeloNoDisponibleException();
		};

		assertThatThrownBy(() -> new Interpretador(() -> caido, "PROMPT", Duration.ofSeconds(1))
				.interpretar("juan vino hoy?", List.of()))
				.isInstanceOf(ModeloNoDisponibleException.class);
		assertThat(intentos).hasValue(1);
	}
}
