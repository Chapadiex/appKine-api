package com.akine.platform.infrastructure.modelo.agente;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.agente.DefinicionDeHerramienta;
import com.akine.platform.spi.modelo.agente.LlamadaAHerramienta;
import com.akine.platform.spi.modelo.agente.MensajeDelAgente;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import com.akine.platform.spi.modelo.agente.ResultadoDeLlamada;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import com.akine.platform.spi.modelo.agente.UsoDelModelo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Proveedor de agente sobre cualquier API compatible con OpenAI ({@code POST {url}/chat/completions}):
 * OpenCode, o Ollama con {@code url = http://localhost:11434/v1}. Timeout, conexion rechazada, 5xx o
 * respuesta ilegible terminan en {@link ModeloNoDisponibleException}. No registra contenido.
 *
 * <p>{@code crudo} del turno es el mensaje {@code assistant} tal cual vino (con {@code tool_calls}); los
 * resultados de herramientas viajan como mensajes {@code role: tool}.
 */
public class CompatibleOpenAiProveedor implements ProveedorDeAgente {

	private static final Logger log = LoggerFactory.getLogger(CompatibleOpenAiProveedor.class);

	private final ObjectMapper json = JsonMapper.builder().build();
	private final RestClient http;
	private final String modelo;
	private final String apiKey;

	public CompatibleOpenAiProveedor(RestClient.Builder builder, String url, String modelo, String apiKey) {
		this.http = builder.baseUrl(url.replaceAll("/+$", "")).build();
		this.modelo = modelo;
		this.apiKey = apiKey;
	}

	@Override
	public String nombre() {
		return "compatible";
	}

	@Override
	public TurnoDelAgente responder(PedidoAlAgente pedido) {
		String cuerpo = json.writeValueAsString(armar(pedido));
		String respuesta;
		try {
			RestClient.RequestBodySpec spec = http.post().uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
			if (apiKey != null && !apiKey.isBlank()) {
				spec = spec.header("Authorization", "Bearer " + apiKey);
			}
			respuesta = spec.body(cuerpo).retrieve().body(String.class);
		}
		catch (RestClientException e) {
			log.warn("Proveedor compatible no disponible: {}", e.getClass().getSimpleName());
			throw new ModeloNoDisponibleException(e);
		}
		return leer(respuesta);
	}

	private ObjectNode armar(PedidoAlAgente pedido) {
		ObjectNode raiz = json.createObjectNode();
		raiz.put("model", modelo);
		ArrayNode mensajes = raiz.putArray("messages");
		if (pedido.system() != null && !pedido.system().isBlank()) {
			mensajes.addObject().put("role", "system").put("content", pedido.system());
		}
		for (MensajeDelAgente m : pedido.mensajes()) {
			switch (m) {
				case MensajeDelAgente.DelUsuario u ->
						mensajes.addObject().put("role", "user").put("content", u.texto());
				case MensajeDelAgente.DelModelo d -> mensajes.add(d.turno().crudo() instanceof JsonNode n
						? n : mensajeDe(d.turno()));
				case MensajeDelAgente.Resultados r -> {
					for (ResultadoDeLlamada res : r.resultados()) {
						mensajes.addObject().put("role", "tool").put("tool_call_id", res.llamadaId())
								.put("content", res.contenidoJson());
					}
				}
			}
		}
		if (pedido.herramientas() != null && !pedido.herramientas().isEmpty()) {
			ArrayNode tools = raiz.putArray("tools");
			for (DefinicionDeHerramienta h : pedido.herramientas()) {
				ObjectNode f = tools.addObject().put("type", "function").putObject("function");
				f.put("name", h.nombre()).put("description", h.descripcion());
				f.set("parameters", json.readTree(h.esquemaJson()));
			}
			raiz.put("tool_choice", "auto");
		}
		return raiz;
	}

	/** Reconstruye el mensaje assistant cuando el turno no trae el crudo del proveedor. */
	private ObjectNode mensajeDe(TurnoDelAgente turno) {
		ObjectNode m = json.createObjectNode().put("role", "assistant");
		m.put("content", turno.texto() == null ? "" : turno.texto());
		if (!turno.llamadas().isEmpty()) {
			ArrayNode calls = m.putArray("tool_calls");
			for (LlamadaAHerramienta l : turno.llamadas()) {
				ObjectNode c = calls.addObject().put("id", l.id()).put("type", "function");
				c.putObject("function").put("name", l.nombre()).put("arguments", l.entradaJson());
			}
		}
		return m;
	}

	private TurnoDelAgente leer(String cuerpo) {
		try {
			JsonNode raiz = json.readTree(cuerpo);
			JsonNode mensaje = raiz.path("choices").path(0).path("message");
			if (mensaje.isMissingNode()) {
				throw new ModeloNoDisponibleException();
			}
			List<LlamadaAHerramienta> llamadas = new ArrayList<>();
			for (JsonNode c : mensaje.path("tool_calls")) {
				String args = c.path("function").path("arguments").asString("{}");
				llamadas.add(new LlamadaAHerramienta(c.path("id").asString(""),
						c.path("function").path("name").asString(""), args.isBlank() ? "{}" : args));
			}
			JsonNode usage = raiz.path("usage");
			UsoDelModelo uso = new UsoDelModelo(usage.path("prompt_tokens").asLong(0),
					usage.path("completion_tokens").asLong(0), 0);
			JsonNode contenido = mensaje.path("content");
			String texto = contenido.isString() ? contenido.asString() : "";
			return new TurnoDelAgente(texto, llamadas, mensaje, uso);
		}
		catch (JacksonException | NullPointerException e) {
			log.warn("Respuesta del proveedor compatible ilegible");
			throw new ModeloNoDisponibleException(e);
		}
	}
}
