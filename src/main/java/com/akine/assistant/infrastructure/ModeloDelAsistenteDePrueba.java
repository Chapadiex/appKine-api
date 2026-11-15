package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.ModeloDeLenguaje;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Determinista, para tests y desarrollo sin Ollama. Por defecto contesta {@code FUERA_DE_ALCANCE};
 * un test que necesita otra interpretacion le pasa la funcion que la arma desde los mensajes.
 */
public class ModeloDelAsistenteDePrueba implements ModeloDeLenguaje {

	static final String FUERA_DE_ALCANCE = "{\"intencion\":\"FUERA_DE_ALCANCE\",\"persona\":null,"
			+ "\"clase\":null,\"periodo\":null,\"reporte\":null}";

	private final Function<List<MensajeChat>, String> respuesta;

	public ModeloDelAsistenteDePrueba() {
		this(mensajes -> FUERA_DE_ALCANCE);
	}

	public ModeloDelAsistenteDePrueba(Function<List<MensajeChat>, String> respuesta) {
		this.respuesta = Objects.requireNonNull(respuesta);
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, Duration plazo) {
		return respuesta.apply(List.copyOf(mensajes));
	}
}
