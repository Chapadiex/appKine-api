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
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.services.blocking.beta.MessageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proveedor del agente sobre la API de mensajes (beta) de Anthropic.
 *
 * <p>El system y las herramientas (en orden alfabetico, con {@code cache_control} en la ultima) forman
 * el prefijo cacheado, asi que no cambian entre vueltas. Pensamiento adaptativo, {@code effort} de las
 * propiedades y fallback del lado del servidor ({@code fallbacks: "default"}). No manda temperature,
 * top_p, top_k, budget_tokens ni tool_choice forzado: el modelo los rechaza con 400.
 *
 * <p>El turno del modelo se guarda entero en {@link TurnoDelAgente#crudo()} (el {@link BetaMessage},
 * con los bloques de pensamiento y su firma) y se reenvia tal cual en la vuelta siguiente.
 */
public class ClaudeProveedor implements ProveedorDeAgente, AutoCloseable {

	static final String BETA_FALLBACK = "server-side-fallback-2026-07-01";
	static final String TEXTO_RECHAZO = "No puedo responder eso.";
	static final String ADVERTENCIA_CORTADA =
			"(La respuesta quedo incompleta: se alcanzo el largo maximo de la respuesta.)";

	private static final Logger log = LoggerFactory.getLogger(ClaudeProveedor.class);
	private static final TypeReference<Map<String, Object>> OBJETO = new TypeReference<>() {
	};

	private final AgenteProperties properties;
	private final AnthropicClient client;
	private final MessageService mensajes;

	/** Crea el cliente HTTP solo si hay key: sin ella {@link #responder} falla y el agente cae al alternativo. */
	public ClaudeProveedor(AgenteProperties properties) {
		this.properties = properties;
		String key = properties.claude().apiKey();
		// Sin reintentos del SDK: el plazo de cada pedido ya es el que le queda a la pregunta, y el
		// agente tiene su propio alternativo.
		this.client = key.isEmpty() ? null
				: AnthropicOkHttpClient.builder().apiKey(key).maxRetries(0).build();
		this.mensajes = client == null ? null : client.beta().messages();
	}

	/** Para tests: un servicio de mensajes armado a mano, sin red. */
	ClaudeProveedor(AgenteProperties properties, MessageService mensajes) {
		this.properties = properties;
		this.client = null;
		this.mensajes = mensajes;
	}

	@Override
	public String nombre() {
		return "claude";
	}

	@Override
	public TurnoDelAgente responder(PedidoAlAgente pedido) {
		if (mensajes == null) {
			throw new ModeloNoDisponibleException(
					new IllegalStateException("Falta la key de Anthropic (AKINE_ANTHROPIC_API_KEY)."));
		}
		MessageCreateParams params = parametros(pedido);
		RequestOptions opciones = pedido.plazo() == null ? RequestOptions.none()
				: RequestOptions.builder().timeout(pedido.plazo()).build();
		BetaMessage respuesta;
		try {
			respuesta = mensajes.create(params, opciones);
		} catch (AnthropicServiceException e) {
			int status = e.statusCode();
			if (status == 408 || status == 429 || status >= 500) {
				throw new ModeloNoDisponibleException(e);
			}
			// 400/401/403/404...: un error de configuracion o del pedido, no una caida. El mensaje del
			// SDK trae el cuerpo del error de la API, nunca la key.
			throw new IllegalStateException("Claude rechazo el pedido (" + status + "): " + e.getMessage(), e);
		} catch (AnthropicIoException | AnthropicRetryableException e) {
			throw new ModeloNoDisponibleException(e);
		}
		return turnoDe(respuesta);
	}

	@Override
	public void close() {
		if (client != null) {
			client.close();
		}
	}

	/** {@link PedidoAlAgente} a los parametros del SDK. */
	MessageCreateParams parametros(PedidoAlAgente pedido) {
		AgenteProperties.Claude claude = properties.claude();
		MessageCreateParams.Builder b = MessageCreateParams.builder()
				.model(claude.modelo())
				.maxTokens(claude.maxTokens())
				.thinking(BetaThinkingConfigAdaptive.builder().build())
				.outputConfig(BetaOutputConfig.builder()
						.effort(BetaOutputConfig.Effort.of(properties.effort()))
						.build())
				.addBeta(BETA_FALLBACK)
				.fallbacks(BetaFallbacksParam.ofDefault());
		if (pedido.system() != null && !pedido.system().isBlank()) {
			b.system(MessageCreateParams.System.ofBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
					.text(pedido.system())
					.cacheControl(BetaCacheControlEphemeral.builder().build())
					.build())));
		}
		List<DefinicionDeHerramienta> herramientas = new ArrayList<>(
				pedido.herramientas() == null ? List.of() : pedido.herramientas());
		herramientas.sort(Comparator.comparing(DefinicionDeHerramienta::nombre));
		for (int i = 0; i < herramientas.size(); i++) {
			b.addTool(herramienta(herramientas.get(i), i == herramientas.size() - 1));
		}
		for (MensajeDelAgente m : pedido.mensajes()) {
			b.addMessage(mensaje(m));
		}
		return b.build();
	}

	/** La respuesta del SDK a un {@link TurnoDelAgente}. */
	static TurnoDelAgente turnoDe(BetaMessage respuesta) {
		StringBuilder texto = new StringBuilder();
		List<LlamadaAHerramienta> llamadas = new ArrayList<>();
		for (BetaContentBlock bloque : respuesta.content()) {
			if (bloque.isText()) {
				texto.append(bloque.asText().text());
			} else if (bloque.isToolUse()) {
				BetaToolUseBlock uso = bloque.asToolUse();
				llamadas.add(new LlamadaAHerramienta(uso.id(), uso.name(), json(uso._input())));
			}
		}
		UsoDelModelo uso = uso(respuesta.usage());
		BetaStopReason motivo = respuesta.stopReason().orElse(null);
		if (BetaStopReason.REFUSAL.equals(motivo)) {
			return new TurnoDelAgente(TEXTO_RECHAZO, List.of(), respuesta, uso);
		}
		if (BetaStopReason.MAX_TOKENS.equals(motivo)) {
			// La ultima llamada puede haber quedado con la entrada cortada: no se ejecuta ninguna.
			log.warn("Claude corto la respuesta por max_tokens ({} tokens de salida)", uso.salida());
			String parcial = texto.toString().strip();
			return new TurnoDelAgente(parcial.isEmpty() ? ADVERTENCIA_CORTADA : parcial + "\n\n" + ADVERTENCIA_CORTADA,
					List.of(), respuesta, uso);
		}
		return new TurnoDelAgente(texto.toString(), llamadas, respuesta, uso);
	}

	private static UsoDelModelo uso(BetaUsage usage) {
		return new UsoDelModelo(usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L));
	}

	private static BetaTool herramienta(DefinicionDeHerramienta d, boolean ultima) {
		Map<String, Object> esquema = objeto(d.esquemaJson());
		BetaTool.InputSchema.Builder schema = BetaTool.InputSchema.builder();
		for (Map.Entry<String, Object> e : esquema.entrySet()) {
			switch (e.getKey()) {
				case "type" -> {
					// Siempre "object": lo pone el SDK.
				}
				case "properties" -> {
					BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
					if (e.getValue() instanceof Map<?, ?> mapa) {
						mapa.forEach((k, v) -> props.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
					}
					schema.properties(props.build());
				}
				case "required" -> {
					List<String> requeridos = new ArrayList<>();
					if (e.getValue() instanceof List<?> lista) {
						lista.forEach(v -> requeridos.add(String.valueOf(v)));
					}
					schema.required(requeridos);
				}
				default -> schema.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
			}
		}
		BetaTool.Builder tool = BetaTool.builder()
				.name(d.nombre())
				.description(d.descripcion())
				.inputSchema(schema.build())
				.strict(true);
		if (ultima) {
			tool.cacheControl(BetaCacheControlEphemeral.builder().build());
		}
		return tool.build();
	}

	private static BetaMessageParam mensaje(MensajeDelAgente m) {
		return switch (m) {
			case MensajeDelAgente.DelUsuario u -> BetaMessageParam.ofUser(u.texto());
			case MensajeDelAgente.DelModelo dm -> delModelo(dm.turno());
			case MensajeDelAgente.Resultados r -> BetaMessageParam.ofUserOfContentBlockParams(
					r.resultados().stream().map(ClaudeProveedor::resultado).toList());
		};
	}

	/** Reenvia el contenido original (con pensamiento y firma); sin crudo, solo el texto. */
	private static BetaMessageParam delModelo(TurnoDelAgente turno) {
		if (turno.crudo() instanceof BetaMessage original) {
			return original.toParam();
		}
		if (turno.crudo() instanceof BetaMessageParam param) {
			return param;
		}
		return BetaMessageParam.ofAssistant(turno.texto() == null ? "" : turno.texto());
	}

	private static BetaContentBlockParam resultado(ResultadoDeLlamada r) {
		return BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
				.toolUseId(r.llamadaId())
				.content(r.contenidoJson() == null ? "" : r.contenidoJson())
				.isError(r.error())
				.build());
	}

	private static Map<String, Object> objeto(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return ObjectMappers.jsonMapper().readValue(json, OBJETO);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Esquema de herramienta invalido: " + e.getOriginalMessage(), e);
		}
	}

	private static String json(JsonValue valor) {
		try {
			return ObjectMappers.jsonMapper().writeValueAsString(valor);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Entrada de herramienta ilegible", e);
		}
	}
}
