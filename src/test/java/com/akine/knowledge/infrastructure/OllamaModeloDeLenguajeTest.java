package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
import com.akine.knowledge.domain.port.EstadisticasDeGeneracion;
import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
				.isInstanceOf(AsistenteNoDisponibleException.class);
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
				.isInstanceOf(AsistenteNoDisponibleException.class);

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
}
