package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.ModeloDeLenguaje;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Determinista, para tests y desarrollo sin Ollama. Por defecto contesta {@code FUERA_DE_ALCANCE};
 * un test que necesita otra interpretacion le pasa la funcion que la arma desde los mensajes, en el
 * constructor o con {@link #responderCon} (los IT, que reciben el bean ya creado).
 */
public class ModeloDelAsistenteDePrueba implements ModeloDeLenguaje {

	static final String FUERA_DE_ALCANCE = "{\"intencion\":\"FUERA_DE_ALCANCE\",\"persona\":null,"
			+ "\"clase\":null,\"periodo\":null,\"reporte\":null}";

	private static final Function<List<MensajeChat>, String> POR_DEFECTO = mensajes -> FUERA_DE_ALCANCE;

	private volatile Function<List<MensajeChat>, String> respuesta;

	public ModeloDelAsistenteDePrueba() {
		this(POR_DEFECTO);
	}

	public ModeloDelAsistenteDePrueba(Function<List<MensajeChat>, String> respuesta) {
		this.respuesta = Objects.requireNonNull(respuesta);
	}

	/** Las proximas llamadas devuelven este JSON tal cual. */
	public void responderJson(String json) {
		Objects.requireNonNull(json);
		this.respuesta = mensajes -> json;
	}

	public void responderCon(Function<List<MensajeChat>, String> respuesta) {
		this.respuesta = Objects.requireNonNull(respuesta);
	}

	/** Vuelve a contestar {@code FUERA_DE_ALCANCE}. */
	public void reiniciar() {
		this.respuesta = POR_DEFECTO;
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, Duration plazo) {
		return respuesta.apply(List.copyOf(mensajes));
	}
}
