package com.akine.assistant.infrastructure.telegram;

import com.akine.assistant.domain.port.ClienteDeTelegram;
import com.akine.assistant.infrastructure.config.TelegramProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La Bot API de Telegram con el {@link HttpClient} del JDK.
 *
 * <p><b>El token va en la URL</b> (asi lo pide Telegram): ningun mensaje de error ni de log incluye
 * la URL, solo el metodo y lo que Telegram dijo.
 */
@Component
class RestClienteDeTelegram implements ClienteDeTelegram {

	/** Los updates que interesan: el resto (miembros que entran, ediciones) ni se piden. */
	private static final List<String> UPDATES = List.of("message", "callback_query");

	private final HttpClient http;
	private final String apiUrl;
	private final Duration plazo;
	private final JsonMapper json = JsonMapper.builder().build();

	RestClienteDeTelegram(TelegramProperties properties) {
		this.plazo = Duration.ofSeconds(properties.timeoutSegundos());
		this.http = HttpClient.newBuilder().connectTimeout(plazo).followRedirects(HttpClient.Redirect.NORMAL).build();
		this.apiUrl = properties.apiUrl().replaceAll("/+$", "");
	}

	@Override
	public IdentidadDelBot conocer(String token) {
		JsonNode bot = llamar(token, "getMe", Map.of());
		return new IdentidadDelBot(bot.path("id").asLong(), bot.path("username").asString(""),
				bot.path("first_name").asString(""));
	}

	@Override
	public List<Novedad> novedades(String token, long offset) {
		JsonNode updates = llamar(token, "getUpdates", Map.of("offset", offset, "timeout", 0, "limit", 50,
				"allowed_updates", UPDATES));
		List<Novedad> novedades = new ArrayList<>();
		for (JsonNode update : updates) {
			Novedad novedad = reducir(update);
			if (novedad != null) {
				novedades.add(novedad);
			}
			else {
				// Un update que no es para el asistente igual hay que confirmarlo, o vuelve en cada sondeo.
				novedades.add(new Novedad(update.path("update_id").asLong(), 0, null, 0, null, null, null, null, null,
						null, null));
			}
		}
		return novedades;
	}

	/** Un mensaje de texto, de voz o de audio, o un boton tocado; {@code null} para todo lo demas. */
	private static Novedad reducir(JsonNode update) {
		long updateId = update.path("update_id").asLong();
		JsonNode boton = update.path("callback_query");
		if (!boton.isMissingNode()) {
			JsonNode de = boton.path("from");
			return new Novedad(updateId, boton.path("message").path("chat").path("id").asLong(), null,
					de.path("id").asLong(), nombre(de), null, null, null, null, boton.path("id").asString(),
					boton.path("data").asString(""));
		}
		JsonNode mensaje = update.path("message");
		if (mensaje.isMissingNode() || mensaje.path("from").path("is_bot").asBoolean(false)) {
			return null;
		}
		JsonNode voz = mensaje.has("voice") ? mensaje.path("voice") : mensaje.path("audio");
		String texto = mensaje.has("text") ? mensaje.path("text").asString() : mensaje.path("caption").asString(null);
		if (texto == null && voz.isMissingNode()) {
			return null;
		}
		JsonNode de = mensaje.path("from");
		return new Novedad(updateId, mensaje.path("chat").path("id").asLong(), mensaje.path("message_id").asLong(),
				de.path("id").asLong(), nombre(de), texto,
				voz.isMissingNode() ? null : voz.path("file_id").asString(),
				voz.isMissingNode() ? null : voz.path("mime_type").asString("audio/ogg"),
				voz.isMissingNode() || !voz.has("file_size") ? null : voz.path("file_size").asLong(),
				null, null);
	}

	private static String nombre(JsonNode de) {
		String nombre = (de.path("first_name").asString("") + " " + de.path("last_name").asString("")).strip();
		return nombre.isEmpty() ? de.path("username").asString("") : nombre;
	}

	@Override
	public void enviar(String token, long chatId, Long responderA, String texto, List<Boton> botones) {
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("chat_id", chatId);
		cuerpo.put("text", texto);
		cuerpo.put("disable_web_page_preview", true);
		if (responderA != null) {
			cuerpo.put("reply_parameters", Map.of("message_id", responderA, "allow_sending_without_reply", true));
		}
		if (!botones.isEmpty()) {
			// Un boton por fila: los nombres de pacientes no entran de a dos.
			cuerpo.put("reply_markup", Map.of("inline_keyboard", botones.stream()
					.map(b -> List.of(Map.of("text", b.texto(), "callback_data", b.datos())))
					.toList()));
		}
		llamar(token, "sendMessage", cuerpo);
	}

	@Override
	public void contestarBoton(String token, String callbackId) {
		try {
			llamar(token, "answerCallbackQuery", Map.of("callback_query_id", callbackId));
		}
		catch (FalloDeTelegram vencido) {
			// Un boton viejo ya no se puede contestar: no impide procesarlo.
		}
	}

	@Override
	public byte[] descargar(String token, String fileId, long maxBytes) {
		JsonNode archivo = llamar(token, "getFile", Map.of("file_id", fileId));
		String ruta = archivo.path("file_path").asString("");
		if (ruta.isEmpty()) {
			throw new FalloDeTelegram("Telegram no devolvio el archivo de audio", false);
		}
		if (archivo.has("file_size") && archivo.path("file_size").asLong() > maxBytes) {
			throw new FalloDeTelegram("El audio supera el tope", false);
		}
		HttpRequest pedido = HttpRequest.newBuilder(URI.create(apiUrl + "/file/bot" + token + "/"
						+ URLEncoder.encode(ruta, StandardCharsets.UTF_8).replace("%2F", "/")))
				.timeout(plazo).GET().build();
		try {
			HttpResponse<byte[]> respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
			if (respuesta.statusCode() != 200) {
				throw new FalloDeTelegram("La descarga del audio respondio " + respuesta.statusCode(), false);
			}
			if (respuesta.body().length > maxBytes) {
				throw new FalloDeTelegram("El audio supera el tope", false);
			}
			return respuesta.body();
		}
		catch (IOException e) {
			throw new FalloDeTelegram("No se pudo descargar el audio: " + e.getClass().getSimpleName(), false);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FalloDeTelegram("Descarga interrumpida", false);
		}
	}

	/** POST con cuerpo JSON. Devuelve {@code result}; {@code ok: false} es {@link FalloDeTelegram}. */
	private JsonNode llamar(String token, String metodo, Map<String, ?> cuerpo) {
		HttpRequest pedido = HttpRequest.newBuilder(URI.create(apiUrl + "/bot" + token + "/" + metodo))
				.timeout(plazo)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
				.build();
		HttpResponse<String> respuesta;
		try {
			respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new FalloDeTelegram(metodo + ": sin conexion con Telegram (" + e.getClass().getSimpleName() + ")",
					false);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FalloDeTelegram(metodo + ": interrumpido", false);
		}
		JsonNode cuerpoRespuesta;
		try {
			cuerpoRespuesta = json.readTree(respuesta.body());
		}
		catch (JacksonException e) {
			throw new FalloDeTelegram(metodo + ": Telegram respondio " + respuesta.statusCode() + " sin JSON", false);
		}
		if (!cuerpoRespuesta.path("ok").asBoolean(false)) {
			int codigo = cuerpoRespuesta.path("error_code").asInt(respuesta.statusCode());
			throw new FalloDeTelegram(metodo + ": " + codigo + " " + cuerpoRespuesta.path("description").asString(""),
					codigo == 401 || codigo == 404);
		}
		return cuerpoRespuesta.path("result");
	}
}
