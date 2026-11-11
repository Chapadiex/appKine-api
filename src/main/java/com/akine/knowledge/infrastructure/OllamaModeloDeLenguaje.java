package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Modelo de lenguaje sobre Ollama ({@code POST {url}/api/chat}). Conexion rechazada, 5xx o plazo
 * vencido terminan en {@link AsistenteNoDisponibleException}. No registra el contenido de los
 * mensajes ni de las respuestas.
 */
public class OllamaModeloDeLenguaje implements ModeloDeLenguaje {

	private static final Logger log = LoggerFactory.getLogger(OllamaModeloDeLenguaje.class);
	private static final int NUM_CTX = 4096;
	private static final ScheduledExecutorService VIGILANTE = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "ollama-plazo");
		t.setDaemon(true);
		return t;
	});

	private final HttpClient http;
	private final ObjectMapper json = JsonMapper.builder().build();
	private final URI endpoint;
	private final String modelo;
	private final String keepAlive;

	public OllamaModeloDeLenguaje(String url, String modelo, String keepAlive) {
		this.endpoint = URI.create(url.replaceAll("/+$", "") + "/api/chat");
		this.modelo = modelo;
		this.keepAlive = keepAlive;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Override
	public String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		HttpRequest request = armar(mensajes, opciones, false, plazo);
		try {
			HttpResponse<String> respuesta = http.send(request, HttpResponse.BodyHandlers.ofString());
			exigirOk(respuesta.statusCode());
			return json.readTree(respuesta.body()).path("message").path("content").asString("");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AsistenteNoDisponibleException(e);
		}
		catch (AsistenteNoDisponibleException e) {
			throw e;
		}
		catch (IOException | RuntimeException e) {
			throw noDisponible(e);
		}
	}

	@Override
	public void generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado) {
		HttpRequest request = armar(mensajes, opciones, true, plazo);
		long limite = System.nanoTime() + plazo.toNanos();
		try {
			HttpResponse<Stream<String>> respuesta = http.send(request, HttpResponse.BodyHandlers.ofLines());
			Stream<String> lineas = respuesta.body();
			// El plazo del request cubre hasta los encabezados: despues lo hace cumplir este corte.
			ScheduledFuture<?> corte = VIGILANTE.schedule(lineas::close,
					Math.max(0, limite - System.nanoTime()), TimeUnit.NANOSECONDS);
			try (lineas) {
				exigirOk(respuesta.statusCode());
				Iterator<String> it = lineas.iterator();
				while (it.hasNext()) {
					if (cancelado.getAsBoolean()) {
						return;
					}
					if (System.nanoTime() > limite) {
						throw new AsistenteNoDisponibleException();
					}
					String linea = it.next();
					if (linea.isBlank()) {
						continue;
					}
					JsonNode nodo = json.readTree(linea);
					String fragmento = nodo.path("message").path("content").asString("");
					if (!fragmento.isEmpty()) {
						alFragmento.accept(fragmento);
					}
					if (nodo.path("done").asBoolean(false)) {
						return;
					}
				}
			}
			finally {
				corte.cancel(false);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AsistenteNoDisponibleException(e);
		}
		catch (AsistenteNoDisponibleException e) {
			throw e;
		}
		catch (IOException | RuntimeException e) {
			if (cancelado.getAsBoolean()) {
				return;
			}
			throw noDisponible(e);
		}
	}

	private HttpRequest armar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, boolean stream, Duration plazo) {
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("model", modelo);
		cuerpo.put("messages", mensajes.stream()
				.map(m -> Map.of("role", rol(m.rol()), "content", m.texto()))
				.toList());
		cuerpo.put("stream", stream);
		cuerpo.put("keep_alive", keepAlive);
		cuerpo.put("options", Map.of(
				"temperature", opciones.temperatura(),
				"num_predict", opciones.maxTokens(),
				"num_ctx", NUM_CTX));
		return HttpRequest.newBuilder(endpoint)
				.timeout(plazo)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
				.build();
	}

	private static String rol(MensajeChat.Rol rol) {
		return switch (rol) {
			case SISTEMA -> "system";
			case USUARIO -> "user";
			case ASISTENTE -> "assistant";
		};
	}

	private static void exigirOk(int status) {
		if (status != 200) {
			throw new AsistenteNoDisponibleException();
		}
	}

	private static AsistenteNoDisponibleException noDisponible(Exception causa) {
		log.warn("Ollama no disponible: {}", causa.getClass().getSimpleName());
		return new AsistenteNoDisponibleException(causa);
	}
}
