package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.assistant.domain.port.Transcriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Transcriptor sobre un servidor Whisper local con la API de OpenAI
 * ({@code POST {url}/v1/audio/transcriptions}, multipart). La implementan speaches
 * (faster-whisper) y otros servidores; el del compose es speaches.
 *
 * <p>Se manda el idioma fijo y un {@code prompt} con vocabulario de kinesiologia, que Whisper usa
 * como contexto para escribir bien esos terminos. No registra el audio ni el texto.
 */
public class WhisperTranscriptor implements Transcriptor {

	private static final Logger log = LoggerFactory.getLogger(WhisperTranscriptor.class);

	private final HttpClient http;
	private final ObjectMapper json = JsonMapper.builder().build();
	private final URI endpoint;
	private final String modelo;
	private final String idioma;
	private final String vocabulario;

	public WhisperTranscriptor(String url, String modelo, String idioma, String vocabulario) {
		this.endpoint = URI.create(url.replaceAll("/+$", "") + "/v1/audio/transcriptions");
		this.modelo = modelo;
		this.idioma = idioma;
		this.vocabulario = vocabulario;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Override
	public String transcribir(byte[] audio, String tipoContenido, Duration plazo) {
		HttpResponse<String> respuesta;
		try {
			respuesta = http.send(armar(audio, tipoContenido, plazo), HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DictadoNoDisponibleException(e);
		}
		catch (IOException | RuntimeException e) {
			log.warn("Whisper no disponible: {}", e.getClass().getSimpleName());
			throw new DictadoNoDisponibleException(e);
		}
		if (respuesta.statusCode() != 200) {
			log.warn("Whisper respondio con status={}", respuesta.statusCode());
			throw new DictadoNoDisponibleException();
		}
		try {
			return json.readTree(respuesta.body()).path("text").asString("").strip();
		}
		catch (JacksonException e) {
			log.warn("Respuesta de Whisper ilegible");
			throw new DictadoNoDisponibleException(e);
		}
	}

	HttpRequest armar(byte[] audio, String tipoContenido, Duration plazo) {
		String limite = "akine-" + UUID.randomUUID();
		ByteArrayOutputStream cuerpo = new ByteArrayOutputStream(audio.length + 1024);
		campo(cuerpo, limite, "model", modelo);
		campo(cuerpo, limite, "language", idioma);
		campo(cuerpo, limite, "response_format", "json");
		campo(cuerpo, limite, "temperature", "0");
		if (vocabulario != null && !vocabulario.isBlank()) {
			campo(cuerpo, limite, "prompt", vocabulario);
		}
		escribir(cuerpo, "--" + limite + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"dictado." + extension(tipoContenido)
				+ "\"\r\nContent-Type: " + tipoContenido + "\r\n\r\n");
		cuerpo.writeBytes(audio);
		escribir(cuerpo, "\r\n--" + limite + "--\r\n");
		return HttpRequest.newBuilder(endpoint)
				.timeout(plazo)
				.header("Content-Type", "multipart/form-data; boundary=" + limite)
				.POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
				.build();
	}

	/** ffmpeg detecta el formato por los bytes, pero algunos servidores miran la extension. */
	static String extension(String tipoContenido) {
		int barra = tipoContenido.indexOf('/');
		String sub = barra < 0 ? "" : tipoContenido.substring(barra + 1);
		return switch (sub) {
			case "mp4", "m4a", "x-m4a", "aac" -> "m4a";
			case "mpeg", "mp3" -> "mp3";
			case "ogg" -> "ogg";
			case "wav", "x-wav", "wave" -> "wav";
			default -> "webm";
		};
	}

	private static void campo(ByteArrayOutputStream cuerpo, String limite, String nombre, String valor) {
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"" + nombre + "\"\r\n\r\n"
				+ valor + "\r\n");
	}

	private static void escribir(ByteArrayOutputStream cuerpo, String texto) {
		cuerpo.writeBytes(texto.getBytes(StandardCharsets.UTF_8));
	}
}
