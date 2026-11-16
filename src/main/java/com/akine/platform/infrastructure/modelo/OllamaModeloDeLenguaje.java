package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.EstadisticasDeGeneracion;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.OpcionesGeneracion;
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
 * Modelo de lenguaje sobre Ollama ({@code POST {url}/api/chat}). Conexion rechazada, status distinto
 * de 2xx, respuesta ilegible o plazo vencido terminan en {@link ModeloNoDisponibleException}. No
 * registra el contenido de los mensajes ni de las respuestas.
 *
 * <p>Une los dos adaptadores que tenian {@code knowledge} ({@code completar} y {@code generar}) y
 * {@code assistant} ({@code completarJson}) (D-3). {@code num_ctx} es 4096 en todas las llamadas, para
 * que Ollama no recargue el modelo al alternar entre consumidores, salvo en {@code completarJson} con
 * {@link OpcionesGeneracion}, que manda {@link OpcionesGeneracion#numCtx()}: las sugerencias de evolucion piden
 * 8192 porque su prompt llega a 3 hallazgos con 12 pasajes, y eso recarga el modelo si otro consumidor lo tenia
 * cargado con 4096.
 *
 * <p>{@code completarJson} usa los parametros que midio el asistente el 15/11
 * ({@code docs/asistente/evaluacion/}): temperatura 0 y 120 tokens, que sobran para un objeto corto.
 */
public class OllamaModeloDeLenguaje implements ModeloDeLenguaje {

	private static final Logger log = LoggerFactory.getLogger(OllamaModeloDeLenguaje.class);
	static final int NUM_CTX = 4096;
	static final int MAX_TOKENS_JSON = 120;
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

	/** Con un cliente HTTP propio que corta la conexion a los 5 s. */
	public OllamaModeloDeLenguaje(String url, String modelo, String keepAlive) {
		this(url, modelo, keepAlive, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	public OllamaModeloDeLenguaje(String url, String modelo, String keepAlive, HttpClient http) {
		this.endpoint = URI.create(url.replaceAll("/+$", "") + "/api/chat");
		this.modelo = modelo;
		this.keepAlive = keepAlive;
		this.http = http;
	}

	@Override
	public String completar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo) {
		return enviar(armar(mensajes, opciones, false, plazo));
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, String esquemaJson, Duration plazo) {
		Map<String, Object> cuerpo = cuerpo(mensajes, false);
		cuerpo.put("format", esquemaJson == null ? "json" : esquema(esquemaJson));
		cuerpo.put("options", Map.of("temperature", 0, "num_predict", MAX_TOKENS_JSON, "num_ctx", NUM_CTX));
		return enviar(pedido(cuerpo, plazo));
	}

	@Override
	public String completarJson(List<MensajeChat> mensajes, String esquemaJson, OpcionesGeneracion opciones,
			Duration plazo) {
		Map<String, Object> cuerpo = cuerpo(mensajes, false);
		cuerpo.put("format", esquemaJson == null ? "json" : esquema(esquemaJson));
		cuerpo.put("options", Map.of("temperature", opciones.temperatura(), "num_predict", opciones.maxTokens(),
				"num_ctx", opciones.numCtx()));
		return enviar(pedido(cuerpo, plazo));
	}

	private String enviar(HttpRequest request) {
		HttpResponse<String> respuesta;
		try {
			respuesta = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ModeloNoDisponibleException(e);
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
	 * plazo lanza {@link ModeloNoDisponibleException}. Lo que lance {@code alFragmento} se propaga
	 * tal cual.
	 */
	@Override
	public EstadisticasDeGeneracion generar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, Duration plazo,
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
						return EstadisticasDeGeneracion.NINGUNA;
					}
					if (!nodo.texto().isEmpty()) {
						alFragmento.accept(nodo.texto());
					}
					if (nodo.fin()) {
						return nodo.estadisticas();
					}
				}
			}
			if (porPlazo.get()) {
				throw new ModeloNoDisponibleException();
			}
			return EstadisticasDeGeneracion.NINGUNA;
		}
		catch (CancelacionDeLaLlamada e) {
			if (porCancelacion.get() && !porPlazo.get()) {
				return EstadisticasDeGeneracion.NINGUNA;
			}
			throw new ModeloNoDisponibleException();
		}
		finally {
			vigia.cancel(false);
			futuro.cancel(true);
		}
	}

	/**
	 * Pide a Ollama cargar el modelo: un {@code /api/chat} sin mensajes con {@code keep_alive}.
	 * Asincrono: el futuro termina con el status HTTP, o con la excepcion si no responde.
	 */
	public CompletableFuture<Integer> precargar(Duration plazo) {
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("model", modelo);
		cuerpo.put("messages", List.of());
		cuerpo.put("keep_alive", keepAlive);
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(plazo)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
	}

	/** Marca interna: la llamada se corto desde afuera (cancelacion, plazo o stream cerrado). */
	private static final class CancelacionDeLaLlamada extends RuntimeException {
		CancelacionDeLaLlamada() {
			super(null, null, false, false);
		}
	}

	private record NodoDeFragmento(String texto, boolean fin, EstadisticasDeGeneracion estadisticas) {
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
			throw new ModeloNoDisponibleException(e);
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
			boolean fin = nodo.path("done").asBoolean(false);
			EstadisticasDeGeneracion estadisticas = fin
					? new EstadisticasDeGeneracion(nodo.path("prompt_eval_count").asLong(0),
							nodo.path("prompt_eval_duration").asLong(0), nodo.path("eval_count").asLong(0),
							nodo.path("eval_duration").asLong(0))
					: EstadisticasDeGeneracion.NINGUNA;
			return new NodoDeFragmento(nodo.path("message").path("content").asString(""), fin, estadisticas);
		}
		catch (JacksonException e) {
			log.warn("Respuesta de Ollama ilegible");
			throw new ModeloNoDisponibleException();
		}
	}

	private String contenido(String cuerpo) {
		try {
			return json.readTree(cuerpo).path("message").path("content").asString("");
		}
		catch (JacksonException e) {
			log.warn("Respuesta de Ollama ilegible");
			throw new ModeloNoDisponibleException();
		}
	}

	private HttpRequest armar(List<MensajeChat> mensajes, OpcionesGeneracion opciones, boolean stream, Duration plazo) {
		Map<String, Object> cuerpo = cuerpo(mensajes, stream);
		if (opciones.json()) {
			cuerpo.put("format", "json");
		}
		cuerpo.put("options", Map.of(
				"temperature", opciones.temperatura().doubleValue(),
				"num_predict", opciones.maxTokens(),
				"num_ctx", NUM_CTX));
		return pedido(cuerpo, plazo);
	}

	private Map<String, Object> cuerpo(List<MensajeChat> mensajes, boolean stream) {
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("model", modelo);
		cuerpo.put("messages", mensajes.stream()
				.map(m -> Map.of("role", rol(m.rol()), "content", m.texto()))
				.toList());
		cuerpo.put("stream", stream);
		cuerpo.put("keep_alive", keepAlive);
		return cuerpo;
	}

	/** El esquema viaja como objeto, no como texto: Ollama lo espera en {@code format}. */
	private JsonNode esquema(String esquemaJson) {
		try {
			return json.readTree(esquemaJson);
		}
		catch (JacksonException e) {
			throw new IllegalArgumentException("El esquema JSON del modelo no es JSON valido", e);
		}
	}

	private HttpRequest pedido(Map<String, Object> cuerpo, Duration plazo) {
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
		if (status / 100 != 2) {
			log.warn("Ollama respondio con status={}", status);
			throw new ModeloNoDisponibleException();
		}
	}

	private static ModeloNoDisponibleException noDisponible(Exception causa) {
		log.warn("Ollama no disponible: {}", causa.getClass().getSimpleName());
		return new ModeloNoDisponibleException(causa);
	}
}
