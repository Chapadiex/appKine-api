package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.ConfiguracionDeModelo;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.MotorDeModelos;
import com.akine.platform.spi.modelo.OpcionesGeneracion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * {@link MotorDeModelos} sobre la API de Ollama ({@code /api/tags}, {@code /api/ps} y
 * {@code /api/chat}). Lee las mismas propiedades que cada consumidor; solo cuenta los que usan
 * {@code tipo: ollama}, esten prendidos o no, para que la pantalla muestre el motor antes de
 * prender la funcion. Asume un solo servidor: la URL es la del primero configurado.
 */
@Component
public class OllamaMotorDeModelos implements MotorDeModelos {

	private static final Logger log = LoggerFactory.getLogger(OllamaMotorDeModelos.class);
	private static final long MB = 1024L * 1024L;

	private static final Duration PLAZO_TRAS_CARGA = Duration.ofSeconds(3);
	static final String OCUPADO = "Ya hay una carga o una prueba del modelo en curso. Proba de nuevo en un momento.";

	private final Semaphore uno = new Semaphore(1);
	private final List<Consumidor> consumidores;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private final ObjectMapper json = JsonMapper.builder().build();

	public OllamaMotorDeModelos(
			@Value("${akine.asistente.modelo.tipo:ollama}") String asistenteTipo,
			@Value("${akine.asistente.modelo.url:http://localhost:11434}") String asistenteUrl,
			@Value("${akine.asistente.modelo.nombre:qwen3:4b-instruct}") String asistenteModelo,
			@Value("${akine.asistente.modelo.keep-alive:2h}") String asistenteKeepAlive,
			@Value("${akine.evolucion.sugerencias.modelo.tipo:prueba}") String sugerenciasTipo,
			@Value("${akine.evolucion.sugerencias.modelo.url:http://localhost:11434}") String sugerenciasUrl,
			@Value("${akine.evolucion.sugerencias.modelo.nombre:qwen3:4b-instruct}") String sugerenciasModelo,
			@Value("${akine.evolucion.sugerencias.modelo.keep-alive:2h}") String sugerenciasKeepAlive,
			@Value("${akine.biblioteca.consulta.tipo:ollama}") String bibliotecaTipo,
			@Value("${akine.biblioteca.consulta.url:http://localhost:11434}") String bibliotecaUrl,
			@Value("${akine.biblioteca.consulta.modelo:qwen3:4b-instruct}") String bibliotecaModelo,
			@Value("${akine.biblioteca.consulta.keep-alive:2h}") String bibliotecaKeepAlive) {
		List<Consumidor> lista = new ArrayList<>();
		agregar(lista, "ASISTENTE", asistenteTipo, asistenteUrl, asistenteModelo, asistenteKeepAlive);
		agregar(lista, "SUGERENCIAS", sugerenciasTipo, sugerenciasUrl, sugerenciasModelo, sugerenciasKeepAlive);
		agregar(lista, "BIBLIOTECA", bibliotecaTipo, bibliotecaUrl, bibliotecaModelo, bibliotecaKeepAlive);
		this.consumidores = List.copyOf(lista);
	}

	private static void agregar(List<Consumidor> lista, String funcion, String tipo, String url, String modelo,
			String keepAlive) {
		if (ConfiguracionDeModelo.OLLAMA.equals(tipo)) {
			lista.add(new Consumidor(funcion, url.replaceAll("/+$", ""), modelo, keepAlive));
		}
	}

	@Override
	public EstadoDelMotor estado(Duration plazo) {
		if (consumidores.isEmpty()) {
			return new EstadoDelMotor(false, null, "Ninguna funcion usa Ollama en este despliegue", List.of());
		}
		String url = consumidores.getFirst().url();
		try {
			List<String> descargados = nombres(get(url + "/api/tags", plazo));
			Map<String, long[]> cargados = new HashMap<>();
			for (JsonNode m : get(url + "/api/ps", plazo).path("models")) {
				cargados.put(normalizar(m.path("name").asString()),
						new long[] {m.path("size").asLong() / MB, m.path("size_vram").asLong() / MB});
			}
			return new EstadoDelMotor(true, url, null, modelos(descargados, cargados));
		}
		catch (IOException | RuntimeException e) {
			log.info("Ollama en {} no respondio: {}", url, e.getClass().getSimpleName());
			return new EstadoDelMotor(false, url, "Ollama no responde en " + url, modelos(List.of(), Map.of()));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new EstadoDelMotor(false, url, "Consulta interrumpida", modelos(List.of(), Map.of()));
		}
	}

	/**
	 * El servidor de modelos es uno para todos los centros: una precarga o una prueba por vez en esta
	 * instancia. Sin esto, el administrador de un centro apretando "Probar" en loop le quita la GPU a
	 * las consultas reales de los demas.
	 */
	@Override
	public EstadoDelMotor precargar(Duration plazo) {
		if (!uno.tryAcquire()) {
			return ocupado();
		}
		try {
			Map<String, Consumidor> unicos = new LinkedHashMap<>();
			consumidores.forEach(c -> unicos.putIfAbsent(c.url() + "|" + normalizar(c.modelo()), c));
			List<CompletableFuture<Integer>> pedidos = unicos.values().stream()
					.map(c -> precargar(c, plazo))
					.toList();
			CompletableFuture.allOf(pedidos.toArray(CompletableFuture[]::new)).join();
			return estado(PLAZO_TRAS_CARGA);
		}
		finally {
			uno.release();
		}
	}

	@Override
	public PruebaDelMotor probar(Duration plazo) {
		if (consumidores.isEmpty()) {
			return new PruebaDelMotor(false, null, 0, "Ninguna funcion usa Ollama en este despliegue");
		}
		Consumidor c = consumidores.getFirst();
		if (!uno.tryAcquire()) {
			return new PruebaDelMotor(false, c.modelo(), 0, OCUPADO);
		}
		long inicio = System.nanoTime();
		try {
			new OllamaModeloDeLenguaje(c.url(), c.modelo(), c.keepAlive(), http).completar(
					List.of(new MensajeChat(MensajeChat.Rol.USUARIO, "Responde solo: OK")),
					new OpcionesGeneracion(BigDecimal.ZERO, 5), plazo);
			return new PruebaDelMotor(true, c.modelo(), (System.nanoTime() - inicio) / 1_000_000, null);
		}
		catch (RuntimeException e) {
			return new PruebaDelMotor(false, c.modelo(), (System.nanoTime() - inicio) / 1_000_000,
					"El modelo no respondio");
		}
		finally {
			uno.release();
		}
	}

	/** Nunca lanza: una URL mal configurada termina como un pedido fallido mas. */
	private CompletableFuture<Integer> precargar(Consumidor c, Duration plazo) {
		try {
			return new OllamaModeloDeLenguaje(c.url(), c.modelo(), c.keepAlive(), http).precargar(plazo)
					.exceptionally(e -> -1);
		}
		catch (RuntimeException e) {
			return CompletableFuture.completedFuture(-1);
		}
	}

	private EstadoDelMotor ocupado() {
		EstadoDelMotor actual = estado(PLAZO_TRAS_CARGA);
		return new EstadoDelMotor(actual.alcanzable(), actual.url(), OCUPADO, actual.modelos());
	}

	private List<ModeloDelMotor> modelos(List<String> descargados, Map<String, long[]> cargados) {
		Map<String, List<String>> funcionesPorModelo = new LinkedHashMap<>();
		for (Consumidor c : consumidores) {
			funcionesPorModelo.computeIfAbsent(c.modelo(), k -> new ArrayList<>()).add(c.funcion());
		}
		return funcionesPorModelo.entrySet().stream().map(e -> {
			String clave = normalizar(e.getKey());
			long[] memoria = cargados.get(clave);
			return new ModeloDelMotor(e.getKey(), List.copyOf(e.getValue()), descargados.contains(clave),
					memoria != null, memoria == null ? 0 : memoria[0], memoria == null ? 0 : memoria[1]);
		}).toList();
	}

	private JsonNode get(String url, Duration plazo) throws IOException, InterruptedException {
		HttpResponse<String> respuesta = http.send(
				HttpRequest.newBuilder(URI.create(url)).timeout(plazo).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		if (respuesta.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + respuesta.statusCode());
		}
		return json.readTree(respuesta.body());
	}

	private static List<String> nombres(JsonNode cuerpo) {
		List<String> nombres = new ArrayList<>();
		cuerpo.path("models").forEach(m -> nombres.add(normalizar(m.path("name").asString())));
		return nombres;
	}

	/** Ollama lista {@code modelo:latest} aunque se lo haya pedido sin etiqueta. */
	static String normalizar(String nombre) {
		return nombre.contains(":") ? nombre : nombre + ":latest";
	}

	private record Consumidor(String funcion, String url, String modelo, String keepAlive) {
	}
}
