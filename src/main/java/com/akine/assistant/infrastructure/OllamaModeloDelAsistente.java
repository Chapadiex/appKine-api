package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El interpretador del asistente sobre Ollama ({@code POST {url}/api/chat}), en modo JSON y sin
 * streaming. Copia acotada de {@code knowledge.infrastructure.OllamaModeloDeLenguaje} (D-3): solo la
 * llamada completa, que es lo unico que el asistente usa.
 *
 * <p>Parametros fijos, los mismos de la medicion del 15/11 ({@code docs/asistente/evaluacion/}):
 * temperatura 0, {@code num_ctx} 4096 —el mismo que la biblioteca, para que Ollama no recargue el
 * modelo al alternar entre las dos— y 120 tokens, que sobran para el objeto del interpretador.
 *
 * <p>No registra el contenido de los mensajes ni de las respuestas.
 */
public class OllamaModeloDelAsistente implements ModeloDeLenguaje {

	private static final Logger log = LoggerFactory.getLogger(OllamaModeloDelAsistente.class);
	static final int NUM_CTX = 4096;
	static final int MAX_TOKENS = 120;

	private final HttpClient http;
	private final ObjectMapper json = JsonMapper.builder().build();
	private final URI endpoint;
	private final String modelo;
	private final String keepAlive;

	public OllamaModeloDelAsistente(String url, String modelo, String keepAlive) {
		this.endpoint = URI.create(url.replaceAll("/+$", "") + "/api/chat");
		this.modelo = modelo;
		this.keepAlive = keepAlive;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, Duration plazo) {
		return completarJson(mensajes, plazo, MAX_TOKENS);
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, Duration plazo, int maxTokens) {
		HttpResponse<String> respuesta;
		try {
			respuesta = http.send(armar(mensajes, plazo, maxTokens), HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ModeloNoDisponibleException(e);
		}
		catch (IOException | RuntimeException e) {
			log.warn("Ollama no disponible para el asistente: {}", e.getClass().getSimpleName());
			throw new ModeloNoDisponibleException(e);
		}
		if (respuesta.statusCode() != 200) {
			log.warn("Ollama respondio al asistente con status={}", respuesta.statusCode());
			throw new ModeloNoDisponibleException();
		}
		return contenido(respuesta.body());
	}

	HttpRequest armar(List<MensajeChat> mensajes, Duration plazo) {
		return armar(mensajes, plazo, MAX_TOKENS);
	}

	HttpRequest armar(List<MensajeChat> mensajes, Duration plazo, int maxTokens) {
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("model", modelo);
		cuerpo.put("messages", mensajes.stream()
				.map(m -> Map.of("role", rol(m.rol()), "content", m.texto()))
				.toList());
		cuerpo.put("stream", false);
		cuerpo.put("keep_alive", keepAlive);
		cuerpo.put("format", "json");
		cuerpo.put("options", Map.of("temperature", 0, "num_predict", maxTokens, "num_ctx", NUM_CTX));
		return HttpRequest.newBuilder(endpoint)
				.timeout(plazo)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
				.build();
	}

	private String contenido(String cuerpo) {
		try {
			return json.readTree(cuerpo).path("message").path("content").asString("");
		}
		catch (JacksonException e) {
			log.warn("Respuesta de Ollama ilegible para el asistente");
			throw new ModeloNoDisponibleException(e);
		}
	}

	private static String rol(MensajeChat.Rol rol) {
		return switch (rol) {
			case SISTEMA -> "system";
			case USUARIO -> "user";
			case ASISTENTE -> "assistant";
		};
	}
}
