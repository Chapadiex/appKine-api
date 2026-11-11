package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
		HttpResponse<String> respuesta;
		try {
			respuesta = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AsistenteNoDisponibleException(e);
		}
		catch (IOException | RuntimeException e) {
			throw noDisponible(e);
		}
		exigirOk(respuesta.statusCode());
		return contenido(respuesta.body());
	}

	/**
	 * Emite fragmentos. Un vigilante revisa cada 250 ms {@code cancelado} y el plazo, tambien mientras
	 * se esperan los encabezados o el proximo token: si se cancela termina sin lanzar; si vence el
	 * plazo lanza {@link AsistenteNoDisponibleException}. Lo que lance {@code alFragmento} se propaga
	 * tal cual.
	 */
	@Override
	public void generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
			Consumer<String> alFragmento, BooleanSupplier cancelado) {
		HttpRequest request = armar(mensajes, opciones, true, plazo);
		long limite = System.nanoTime() + plazo.toNanos();
		CompletableFuture<HttpResponse<Stream<String>>> futuro =
				http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
		AtomicBoolean porCancelacion = new AtomicBoolean();
		AtomicBoolean porPlazo = new AtomicBoolean();
		AtomicReference<Stream<String>> abierto = new AtomicReference<>();
		ScheduledFuture<?> vigia = VIGILANTE.scheduleAtFixedRate(() -> {
			try {
				if (cancelado.getAsBoolean()) {
					porCancelacion.set(true);
				}
				else if (System.nanoTime() > limite) {
					porPlazo.set(true);
				}
				else {
					return;
				}
				futuro.cancel(true);
				Stream<String> lineas = abierto.get();
				if (lineas != null) {
					lineas.close();
				}
			}
			catch (RuntimeException e) {
				// el vigilante no debe morir: el proximo ciclo reintenta
			}
		}, 0, 250, TimeUnit.MILLISECONDS);
		try {
			HttpResponse<Stream<String>> respuesta = esperar(futuro);
			Stream<String> lineas = respuesta.body();
			abierto.set(lineas);
			try (lineas) {
				exigirOk(respuesta.statusCode());
				Iterator<String> it = lineas.iterator();
				while (!porCancelacion.get() && !porPlazo.get() && siguiente(it)) {
					String linea = it.next();
					if (linea.isBlank()) {
						continue;
					}
					NodoDeFragmento nodo = leer(linea);
					if (porCancelacion.get() || cancelado.getAsBoolean()) {
						return;
					}
					if (!nodo.texto().isEmpty()) {
						alFragmento.accept(nodo.texto());
					}
					if (nodo.fin()) {
						return;
					}
				}
			}
			if (porPlazo.get()) {
				throw new AsistenteNoDisponibleException();
			}
		}
		catch (CancelacionDeLaLlamada e) {
			if (porCancelacion.get() && !porPlazo.get()) {
				return;
			}
			throw new AsistenteNoDisponibleException();
		}
		finally {
			vigia.cancel(false);
			futuro.cancel(true);
		}
	}

	/** Marca interna: la llamada se corto desde afuera (cancelacion, plazo o stream cerrado). */
	private static final class CancelacionDeLaLlamada extends RuntimeException {
		CancelacionDeLaLlamada() {
			super(null, null, false, false);
		}
	}

	private record NodoDeFragmento(String texto, boolean fin) {
	}

	private static HttpResponse<Stream<String>> esperar(CompletableFuture<HttpResponse<Stream<String>>> futuro) {
		try {
			return futuro.get();
		}
		catch (CancellationException e) {
			throw new CancelacionDeLaLlamada();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AsistenteNoDisponibleException(e);
		}
		catch (ExecutionException e) {
			throw noDisponible(e.getCause() instanceof Exception c ? c : e);
		}
	}

	/** hasNext() puede bloquearse; si el vigilante cierra el stream o hay un error de lectura, corta. */
	private static boolean siguiente(Iterator<String> it) {
		try {
			return it.hasNext();
		}
		catch (RuntimeException e) {
			throw new CancelacionDeLaLlamada();
		}
	}

	private NodoDeFragmento leer(String linea) {
		try {
			JsonNode nodo = json.readTree(linea);
			return new NodoDeFragmento(nodo.path("message").path("content").asString(""),
					nodo.path("done").asBoolean(false));
		}
		catch (JacksonException e) {
			log.warn("Respuesta de Ollama ilegible");
			throw new AsistenteNoDisponibleException();
		}
	}

	private String contenido(String cuerpo) {
		try {
			return json.readTree(cuerpo).path("message").path("content").asString("");
		}
		catch (JacksonException e) {
			log.warn("Respuesta de Ollama ilegible");
			throw new AsistenteNoDisponibleException();
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
		if (opciones.json()) {
			cuerpo.put("format", "json");
		}
		cuerpo.put("options", Map.of(
				"temperature", opciones.temperatura().doubleValue(),
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
