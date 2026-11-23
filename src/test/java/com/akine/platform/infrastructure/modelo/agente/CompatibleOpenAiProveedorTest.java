package com.akine.platform.infrastructure.modelo.agente;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.agente.DefinicionDeHerramienta;
import com.akine.platform.spi.modelo.agente.MensajeDelAgente;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.ResultadoDeLlamada;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CompatibleOpenAiProveedorTest {

	private static final String RESPUESTA_CON_LLAMADA = """
			{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
			 {"id":"call_1","type":"function","function":{"name":"contar","arguments":"{\\"x\\":1}"}}]}}],
			 "usage":{"prompt_tokens":11,"completion_tokens":5}}""";

	private MockRestServiceServer servidor;
	private CompatibleOpenAiProveedor proveedor;

	@BeforeEach
	void preparar() {
		RestClient.Builder builder = RestClient.builder();
		servidor = MockRestServiceServer.bindTo(builder).build();
		proveedor = new CompatibleOpenAiProveedor(builder, "http://modelo/v1/", "m", "secreta");
	}

	private PedidoAlAgente pedido(List<MensajeDelAgente> mensajes) {
		return new PedidoAlAgente("sos un asistente",
				List.of(new DefinicionDeHerramienta("contar", "cuenta", "{\"type\":\"object\"}")), mensajes,
				Duration.ofSeconds(5));
	}

	@Test
	void traduceToolCallsYReenviaElResultadoComoMensajeTool() {
		servidor.expect(requestTo("http://modelo/v1/chat/completions")).andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer secreta"))
				.andExpect(jsonPath("$.messages[0].role").value("system"))
				.andExpect(jsonPath("$.tools[0].type").value("function"))
				.andExpect(jsonPath("$.tools[0].function.name").value("contar"))
				.andExpect(jsonPath("$.tool_choice").value("auto"))
				.andRespond(withSuccess(RESPUESTA_CON_LLAMADA, MediaType.APPLICATION_JSON));
		TurnoDelAgente turno = proveedor.responder(pedido(List.of(new MensajeDelAgente.DelUsuario("cuantos?"))));

		assertThat(turno.terminado()).isFalse();
		assertThat(turno.llamadas()).singleElement().satisfies(l -> {
			assertThat(l.id()).isEqualTo("call_1");
			assertThat(l.nombre()).isEqualTo("contar");
			assertThat(l.entradaJson()).isEqualTo("{\"x\":1}");
		});
		assertThat(turno.uso().entrada()).isEqualTo(11);
		assertThat(turno.uso().salida()).isEqualTo(5);

		servidor.reset();
		servidor.expect(requestTo("http://modelo/v1/chat/completions"))
				.andExpect(jsonPath("$.messages[2].role").value("assistant"))
				.andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_1"))
				.andExpect(jsonPath("$.messages[3].role").value("tool"))
				.andExpect(jsonPath("$.messages[3].tool_call_id").value("call_1"))
				.andExpect(jsonPath("$.messages[3].content").value("{\"n\":3}"))
				.andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Son 3\"}}]}",
						MediaType.APPLICATION_JSON));
		TurnoDelAgente fin = proveedor.responder(pedido(List.of(new MensajeDelAgente.DelUsuario("cuantos?"),
				new MensajeDelAgente.DelModelo(turno),
				new MensajeDelAgente.Resultados(List.of(new ResultadoDeLlamada("call_1", "{\"n\":3}", false))))));

		assertThat(fin.terminado()).isTrue();
		assertThat(fin.texto()).isEqualTo("Son 3");
		servidor.verify();
	}

	@Test
	void argumentosComoObjetoSeConservan() {
		servidor.expect(requestTo("http://modelo/v1/chat/completions")).andRespond(withSuccess(
				"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c2\","
						+ "\"function\":{\"name\":\"contar\",\"arguments\":{\"x\":2}}}]}}]}",
				MediaType.APPLICATION_JSON));
		TurnoDelAgente turno = proveedor.responder(pedido(List.of(new MensajeDelAgente.DelUsuario("hola"))));
		assertThat(turno.llamadas().get(0).entradaJson()).isEqualTo("{\"x\":2}");
	}

	@Test
	void un5xxEsModeloNoDisponible() {
		servidor.expect(requestTo("http://modelo/v1/chat/completions"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY));
		assertThatThrownBy(() -> proveedor.responder(pedido(List.of(new MensajeDelAgente.DelUsuario("hola")))))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}
}
