package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.port.MensajeChat;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaModeloDelAsistenteTest {

	private static final List<MensajeChat> MENSAJES = List.of(
			new MensajeChat(MensajeChat.Rol.SISTEMA, "interpreta"),
			new MensajeChat(MensajeChat.Rol.USUARIO, "juan vino hoy?"));

	private HttpServer servidor;
	private final AtomicReference<String> pedido = new AtomicReference<>();

	@AfterEach
	void apagar() {
		if (servidor != null) {
			servidor.stop(0);
		}
	}

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

		String respuesta = new OllamaModeloDelAsistente(url, "qwen3:4b-instruct", "2h")
				.completarJson(MENSAJES, Duration.ofSeconds(5));

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
	void un_error_del_servidor_es_modelo_no_disponible() throws IOException {
		String url = levantar(500, "{\"error\":\"model not found\"}");

		assertThatThrownBy(() -> new OllamaModeloDelAsistente(url, "x", "2h")
				.completarJson(MENSAJES, Duration.ofSeconds(5)))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}

	@Test
	void sin_servidor_es_modelo_no_disponible() {
		// Puerto 9 (discard): nadie escucha ahi en la maquina de desarrollo ni en el CI.
		assertThatThrownBy(() -> new OllamaModeloDelAsistente("http://127.0.0.1:9", "x", "2h")
				.completarJson(MENSAJES, Duration.ofSeconds(2)))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}
}
