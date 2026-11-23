package com.akine.platform.infrastructure.modelo.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.agente.DefinicionDeHerramienta;
import com.akine.platform.spi.modelo.agente.LlamadaAHerramienta;
import com.akine.platform.spi.modelo.agente.MensajeDelAgente;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.ResultadoDeLlamada;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Traduccion ida y vuelta entre los puertos del agente y el SDK de Anthropic, sin red. */
class ClaudeProveedorTest {

	private static final AgenteProperties PROPS = new AgenteProperties("claude", "", "high", 8, 30,
			new AgenteProperties.Claude("", "claude-opus-5-5", 16000), null);

	private static final String ESQUEMA = """
			{"type":"object","properties":{"fecha":{"type":"string"}},"required":["fecha"],
			 "additionalProperties":false}""";

	/** Con una propiedad opcional: no admite strict. */
	private static final String ESQUEMA_CON_OPCIONAL = """
			{"type":"object","properties":{"nombre":{"type":"string"},"dni":{"type":"string"}},
			 "required":["nombre"],"additionalProperties":false}""";

	/** Un turno del modelo con pensamiento y una llamada, como lo devolveria la API. */
	private static final String RESPUESTA_CON_LLAMADA = """
			{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
			 "content":[
			   {"type":"thinking","thinking":"Busco los turnos.","signature":"firma-1"},
			   {"type":"text","text":"Miro la agenda."},
			   {"type":"tool_use","id":"toolu_1","name":"turnos_del_dia","input":{"fecha":"2026-11-23"}}],
			 "stop_reason":"tool_use","stop_sequence":null,
			 "usage":{"input_tokens":120,"output_tokens":40,"cache_read_input_tokens":900,
			          "cache_creation_input_tokens":0}}""";

	@Test
	void armaLosParametrosConPrefijoCacheadoYElTurnoAnteriorIntacto() throws Exception {
		BetaMessage anterior = mensaje(RESPUESTA_CON_LLAMADA);
		var turnoAnterior = new TurnoDelAgente("Miro la agenda.", List.of(), anterior, null);
		var pedido = new PedidoAlAgente("Sos el asistente.",
				List.of(new DefinicionDeHerramienta("turnos_del_dia", "Turnos de un dia", ESQUEMA),
						new DefinicionDeHerramienta("buscar_paciente", "Busca", ESQUEMA_CON_OPCIONAL)),
				List.of(new MensajeDelAgente.DelUsuario("Turnos de hoy?"),
						new MensajeDelAgente.DelModelo(turnoAnterior),
						new MensajeDelAgente.Resultados(List.of(new ResultadoDeLlamada("toolu_1", "{\"total\":3}", false)))),
				Duration.ofSeconds(20));

		MessageCreateParams params = new ClaudeProveedor(PROPS, null).parametros(pedido);
		JsonNode cuerpo = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));

		assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
		assertThat(params.maxTokens()).isEqualTo(16000);
		assertThat(params.betas().orElseThrow()).extracting(b -> b.asString())
				.containsExactly(ClaudeProveedor.BETA_FALLBACK);
		assertThat(cuerpo.path("fallbacks").asText()).isEqualTo("default");
		assertThat(cuerpo.path("thinking").path("type").asText()).isEqualTo("adaptive");
		assertThat(cuerpo.path("output_config").path("effort").asText()).isEqualTo("high");
		assertThat(cuerpo.has("temperature")).isFalse();
		assertThat(cuerpo.has("tool_choice")).isFalse();
		// Cacheo automatico del historial: system + ultima herramienta + este = 3 breakpoints.
		assertThat(cuerpo.path("cache_control").path("type").asText()).isEqualTo("ephemeral");

		var system = params.system().orElseThrow().asBetaTextBlockParams();
		assertThat(system).singleElement().satisfies(s -> {
			assertThat(s.text()).isEqualTo("Sos el asistente.");
			assertThat(s.cacheControl()).isPresent();
		});

		List<BetaTool> tools = params.tools().orElseThrow().stream().map(t -> t.betaTool().orElseThrow()).toList();
		assertThat(tools).extracting(BetaTool::name).containsExactly("buscar_paciente", "turnos_del_dia");
		assertThat(tools.get(0).strict()).contains(false);
		assertThat(tools.get(1).strict()).contains(true);
		assertThat(tools.get(0).cacheControl()).isEmpty();
		assertThat(tools.get(1).cacheControl()).isPresent();
		assertThat(tools.get(1).inputSchema().required()).contains(List.of("fecha"));
		assertThat(cuerpo.path("tools").get(1).path("input_schema").path("additionalProperties").asBoolean(true))
				.isFalse();

		List<BetaMessageParam> mensajes = params.messages();
		assertThat(mensajes).hasSize(3);
		JsonNode asistente = cuerpo.path("messages").get(1);
		assertThat(asistente.path("role").asText()).isEqualTo("assistant");
		assertThat(asistente.path("content").get(0).path("type").asText()).isEqualTo("thinking");
		assertThat(asistente.path("content").get(0).path("signature").asText()).isEqualTo("firma-1");
		assertThat(asistente.path("content").get(2).path("id").asText()).isEqualTo("toolu_1");
		JsonNode resultado = cuerpo.path("messages").get(2).path("content").get(0);
		assertThat(resultado.path("type").asText()).isEqualTo("tool_result");
		assertThat(resultado.path("tool_use_id").asText()).isEqualTo("toolu_1");
		assertThat(resultado.path("content").asText()).isEqualTo("{\"total\":3}");
		assertThat(resultado.path("is_error").asBoolean(true)).isFalse();
	}

	@Test
	void traduceLaRespuestaConLlamadasYUso() throws Exception {
		BetaMessage respuesta = mensaje(RESPUESTA_CON_LLAMADA);

		TurnoDelAgente turno = ClaudeProveedor.turnoDe(respuesta);

		assertThat(turno.texto()).isEqualTo("Miro la agenda.");
		assertThat(turno.terminado()).isFalse();
		assertThat(turno.llamadas()).singleElement().satisfies(l -> {
			assertThat(l.id()).isEqualTo("toolu_1");
			assertThat(l.nombre()).isEqualTo("turnos_del_dia");
			assertThat(ObjectMappers.jsonMapper().readTree(l.entradaJson()).path("fecha").asText())
					.isEqualTo("2026-11-23");
		});
		assertThat(turno.crudo()).isSameAs(respuesta);
		assertThat(turno.uso().entrada()).isEqualTo(120);
		assertThat(turno.uso().salida()).isEqualTo(40);
		assertThat(turno.uso().cacheLeida()).isEqualTo(900);
	}

	@Test
	void unRechazoTerminaSinLlamadas() throws Exception {
		BetaMessage respuesta = mensaje(RESPUESTA_CON_LLAMADA.replace("\"stop_reason\":\"tool_use\"",
				"\"stop_reason\":\"refusal\""));

		TurnoDelAgente turno = ClaudeProveedor.turnoDe(respuesta);

		assertThat(turno.texto()).isEqualTo(ClaudeProveedor.TEXTO_RECHAZO);
		assertThat(turno.terminado()).isTrue();
		// No se reenvia el contenido rechazado ni su tool_use sin tool_result.
		assertThat(turno.crudo()).isNull();
	}

	@Test
	void unaRespuestaCortadaDevuelveElParcialConAdvertencia() throws Exception {
		BetaMessage respuesta = mensaje(RESPUESTA_CON_LLAMADA.replace("\"stop_reason\":\"tool_use\"",
				"\"stop_reason\":\"max_tokens\""));

		TurnoDelAgente turno = ClaudeProveedor.turnoDe(respuesta);

		assertThat(turno.texto()).startsWith("Miro la agenda.").endsWith(ClaudeProveedor.ADVERTENCIA_CORTADA);
		assertThat(turno.terminado()).isTrue();
		assertThat(turno.crudo()).isNull();

		// Reenviado en la vuelta siguiente: solo el texto, ningun tool_use huerfano.
		var pedido = new PedidoAlAgente("s", List.of(), List.of(new MensajeDelAgente.DelUsuario("hola"),
				new MensajeDelAgente.DelModelo(turno), new MensajeDelAgente.DelUsuario("y?")), Duration.ofSeconds(5));
		JsonNode asistente = cuerpo(new ClaudeProveedor(PROPS, null).parametros(pedido)).path("messages").get(1);
		assertThat(asistente.path("content")).singleElement()
				.satisfies(b -> assertThat(b.path("type").asText()).isEqualTo("text"));
	}

	@Test
	void sinCrudoRearmaElTurnoConTextoYLlamadas() throws Exception {
		var turno = new TurnoDelAgente("Busco.",
				List.of(new LlamadaAHerramienta("call_9", "turnos_del_dia", "{\"fecha\":\"2026-11-23\"}")), null, null);
		var pedido = new PedidoAlAgente("s", List.of(), List.of(new MensajeDelAgente.DelUsuario("hola"),
				new MensajeDelAgente.DelModelo(turno),
				new MensajeDelAgente.Resultados(List.of(new ResultadoDeLlamada("call_9", "{}", false)))),
				Duration.ofSeconds(5));

		JsonNode contenido = cuerpo(new ClaudeProveedor(PROPS, null).parametros(pedido))
				.path("messages").get(1).path("content");

		assertThat(contenido.get(0).path("text").asText()).isEqualTo("Busco.");
		assertThat(contenido.get(1).path("type").asText()).isEqualTo("tool_use");
		assertThat(contenido.get(1).path("id").asText()).isEqualTo("call_9");
		assertThat(contenido.get(1).path("input").path("fecha").asText()).isEqualTo("2026-11-23");
	}

	@Test
	void unEffortInvalidoFallaAlArrancar() {
		assertThatThrownBy(() -> new AgenteProperties("claude", "", "altisimo", 8, 30, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sinKeyNoEstaDisponible() {
		var pedido = new PedidoAlAgente("s", List.of(), List.of(new MensajeDelAgente.DelUsuario("hola")),
				Duration.ofSeconds(1));

		assertThatThrownBy(() -> new ClaudeProveedor(PROPS).responder(pedido))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}

	private static JsonNode cuerpo(MessageCreateParams params) throws Exception {
		return ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
	}

	private static BetaMessage mensaje(String json) throws Exception {
		return ObjectMappers.jsonMapper().readValue(json, BetaMessage.class);
	}
}
