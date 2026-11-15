package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.EstadisticasDeGeneracion;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.OpcionesGeneracion;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaModeloDeLenguajeTest {

	private static final List<MensajeChat> MENSAJES = List.of(new MensajeChat(MensajeChat.Rol.USUARIO, "hola"));
	private static final OpcionesGeneracion OPCIONES = new OpcionesGeneracion(new java.math.BigDecimal("0.2"), 400);
	private static final Duration PLAZO = Duration.ofSeconds(2);

	private HttpServer servidor;
	private final java.util.concurrent.CountDownLatch liberar = new java.util.concurrent.CountDownLatch(1);

	@AfterEach
	void apagar() {
		liberar.countDown();
		if (servidor != null) {
			servidor.stop(0);
		}
	}

	private OllamaModeloDeLenguaje conServidorNdjson() throws Exception {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/api/chat", exchange -> {
			byte[] cuerpo = ("{\"message\":{\"content\":\"Hola\"},\"done\":false}\n"
					+ "{\"message\":{\"content\":\" mundo\"},\"done\":false}\n"
					+ "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":120,"
					+ "\"prompt_eval_duration\":400000000,\"eval_count\":40,\"eval_duration\":2000000000}\n")
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
			exchange.sendResponseHeaders(200, cuerpo.length);
			exchange.getResponseBody().write(cuerpo);
			exchange.close();
		});
		servidor.start();
		return new OllamaModeloDeLenguaje("http://127.0.0.1:" + servidor.getAddress().getPort(), "qwen2.5:1.5b", "30m");
	}

	@Test
	void generarEntregaLosFragmentosEnOrden() throws Exception {
		OllamaModeloDeLenguaje modelo = conServidorNdjson();
		List<String> recibidos = new ArrayList<>();

		EstadisticasDeGeneracion estadisticas = modelo.generar(MENSAJES, OPCIONES, PLAZO, recibidos::add, () -> false);

		assertThat(recibidos).containsExactly("Hola", " mundo");
		assertThat(estadisticas).isEqualTo(new EstadisticasDeGeneracion(120, 400_000_000L, 40, 2_000_000_000L));
		assertThat(estadisticas.tokensPorSegundo()).isEqualTo(20.0);
	}

	@Test
	void generarCortaCuandoSeCancela() throws Exception {
		OllamaModeloDeLenguaje modelo = conServidorNdjson();
		List<String> recibidos = new ArrayList<>();
		AtomicBoolean cancelado = new AtomicBoolean();

		modelo.generar(MENSAJES, OPCIONES, PLAZO, f -> {
			recibidos.add(f);
			cancelado.set(true);
		}, cancelado::get);

		assertThat(recibidos).containsExactly("Hola");
	}

	@Test
	void completarConElServidorApagadoLanzaAsistenteNoDisponible() throws Exception {
		HttpServer apagado = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		int puerto = apagado.getAddress().getPort();
		apagado.stop(0);
		OllamaModeloDeLenguaje modelo = new OllamaModeloDeLenguaje("http://127.0.0.1:" + puerto, "qwen2.5:1.5b", "30m");

		assertThatThrownBy(() -> modelo.completar(MENSAJES, OPCIONES, PLAZO))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}

	/** Manda los encabezados y despues nada: ni una linea. */
	private OllamaModeloDeLenguaje conServidorMudo() throws Exception {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/api/chat", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().flush();
			try {
				liberar.await(15, java.util.concurrent.TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		servidor.start();
		return new OllamaModeloDeLenguaje("http://127.0.0.1:" + servidor.getAddress().getPort(), "qwen2.5:1.5b", "30m");
	}

	@Test
	void generarSinLineasVenceElPlazoYLanzaAsistenteNoDisponible() throws Exception {
		OllamaModeloDeLenguaje modelo = conServidorMudo();
		long inicio = System.nanoTime();

		assertThatThrownBy(() -> modelo.generar(MENSAJES, OPCIONES, Duration.ofSeconds(1), f -> { }, () -> false))
				.isInstanceOf(ModeloNoDisponibleException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void generarCanceladoConElServidorMudoVuelveSinLanzar() throws Exception {
		OllamaModeloDeLenguaje modelo = conServidorMudo();
		long inicio = System.nanoTime();
		long cancelarA = inicio + Duration.ofMillis(300).toNanos();

		assertThatCode(() -> modelo.generar(MENSAJES, OPCIONES, Duration.ofSeconds(10), f -> { },
				() -> System.nanoTime() > cancelarA)).doesNotThrowAnyException();

		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
	}

	@Test
	void lasExcepcionesDelConsumidorSePropaganTalCual() throws Exception {
		OllamaModeloDeLenguaje modelo = conServidorNdjson();

		assertThatThrownBy(() -> modelo.generar(MENSAJES, OPCIONES, PLAZO, f -> {
			throw new IllegalStateException("escritura SSE fallida");
		}, () -> false)).isInstanceOf(IllegalStateException.class).hasMessage("escritura SSE fallida");
	}

	@Test
	void completarEnModoJsonPideFormatJson() throws Exception {
		List<String> pedidos = new ArrayList<>();
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/api/chat", exchange -> {
			pedidos.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] cuerpo = "{\"message\":{\"content\":\"{}\"},\"done\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, cuerpo.length);
			exchange.getResponseBody().write(cuerpo);
			exchange.close();
		});
		servidor.start();
		OllamaModeloDeLenguaje modelo = new OllamaModeloDeLenguaje("http://127.0.0.1:" + servidor.getAddress().getPort(), "qwen2.5:1.5b", "30m");

		modelo.completar(MENSAJES, new OpcionesGeneracion(java.math.BigDecimal.ZERO, 160, true), PLAZO);
		modelo.completar(MENSAJES, OPCIONES, PLAZO);

		assertThat(pedidos.get(0)).contains("\"format\":\"json\"");
		assertThat(pedidos.get(1)).doesNotContain("\"format\"");
	}

	// completarJson: los casos del adaptador del asistente (OllamaModeloDelAsistenteTest), que se unio a este.

	private static final List<MensajeChat> MENSAJES_ASISTENTE = List.of(
			new MensajeChat(MensajeChat.Rol.SISTEMA, "interpreta"),
			new MensajeChat(MensajeChat.Rol.USUARIO, "juan vino hoy?"));

	private final AtomicReference<String> pedido = new AtomicReference<>();

	private String levantar(int status, String cuerpo) throws IOException {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/api/chat", intercambio -> {
			pedido.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
			intercambio.sendResponseHeaders(status, bytes.length);
			intercambio.getResponseBody().write(bytes);
			intercambio.close();
		});
		servidor.start();
		return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/";
	}

	@Test
	void pide_json_sin_streaming_con_los_parametros_medidos_y_devuelve_el_contenido() throws IOException {
		String url = levantar(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"intencion\\\":\\\"HOY\\\"}\"}}");

		String respuesta = new OllamaModeloDeLenguaje(url, "qwen3:4b-instruct", "2h")
				.completarJson(MENSAJES_ASISTENTE, null, Duration.ofSeconds(5));

		assertThat(respuesta).isEqualTo("{\"intencion\":\"HOY\"}");
		JsonNode cuerpo = JsonMapper.builder().build().readTree(pedido.get());
		assertThat(cuerpo.path("model").asString()).isEqualTo("qwen3:4b-instruct");
		assertThat(cuerpo.path("format").asString()).isEqualTo("json");
		assertThat(cuerpo.path("stream").asBoolean()).isFalse();
		assertThat(cuerpo.path("options").path("temperature").asInt()).isZero();
		assertThat(cuerpo.path("options").path("num_ctx").asInt()).isEqualTo(4096);
		assertThat(cuerpo.path("messages").get(0).path("role").asString()).isEqualTo("system");
		assertThat(cuerpo.path("messages").get(1).path("content").asString()).isEqualTo("juan vino hoy?");
	}

	@Test
	void completarJson_con_opciones_manda_temperatura_tope_y_num_ctx_de_las_opciones() throws IOException {
		String url = levantar(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}");

		new OllamaModeloDeLenguaje(url, "qwen3:4b-instruct", "2h").completarJson(MENSAJES_ASISTENTE, null,
				new OpcionesGeneracion(new java.math.BigDecimal("0.2"), 1500, true, 8192), Duration.ofSeconds(5));

		JsonNode opciones = JsonMapper.builder().build().readTree(pedido.get()).path("options");
		assertThat(opciones.path("temperature").asDouble()).isEqualTo(0.2);
		assertThat(opciones.path("num_predict").asInt()).isEqualTo(1500);
		assertThat(opciones.path("num_ctx").asInt()).isEqualTo(8192);
	}

	@Test
	void un_error_del_servidor_es_modelo_no_disponible() throws IOException {
		String url = levantar(500, "{\"error\":\"model not found\"}");

		assertThatThrownBy(() -> new OllamaModeloDeLenguaje(url, "x", "2h")
				.completarJson(MENSAJES_ASISTENTE, null, Duration.ofSeconds(5)))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}

	@Test
	void sin_servidor_es_modelo_no_disponible() {
		// Puerto 9 (discard): nadie escucha ahi en la maquina de desarrollo ni en el CI.
		assertThatThrownBy(() -> new OllamaModeloDeLenguaje("http://127.0.0.1:9", "x", "2h")
				.completarJson(MENSAJES_ASISTENTE, null, Duration.ofSeconds(2)))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}
}
