package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaModeloDeLenguajeTest {

	private static final List<MensajeChat> MENSAJES = List.of(new MensajeChat(MensajeChat.Rol.USUARIO, "hola"));
	private static final OpcionesGeneracion OPCIONES = new OpcionesGeneracion(0.2, 400);
	private static final Duration PLAZO = Duration.ofSeconds(10);

	private HttpServer servidor;

	@AfterEach
	void apagar() {
		if (servidor != null) {
			servidor.stop(0);
		}
	}

	private OllamaModeloDeLenguaje conServidorNdjson() throws Exception {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/api/chat", exchange -> {
			byte[] cuerpo = ("{\"message\":{\"content\":\"Hola\"},\"done\":false}\n"
					+ "{\"message\":{\"content\":\" mundo\"},\"done\":false}\n"
					+ "{\"message\":{\"content\":\"\"},\"done\":true}\n").getBytes(StandardCharsets.UTF_8);
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

		modelo.generar(MENSAJES, OPCIONES, PLAZO, recibidos::add, () -> false);

		assertThat(recibidos).containsExactly("Hola", " mundo");
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
}
