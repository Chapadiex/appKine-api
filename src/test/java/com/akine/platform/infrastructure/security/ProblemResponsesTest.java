package com.akine.platform.infrastructure.security;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El escritor de errores de la cadena de filtros.
 *
 * <p>La forma del cuerpo no es cosmetica: el frontend ramifica por {@code type} y esta clase es
 * la unica que lo produce para los errores que ocurren fuera del {@code DispatcherServlet}, o
 * sea antes de que exista un {@code @RestControllerAdvice} que pueda intervenir.
 */
class ProblemResponsesTest {

	@Test
	@DisplayName("escribe RFC 7807 con los cinco campos y el content type correcto")
	void escribe_rfc_7807() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/algo");
		MockHttpServletResponse response = new MockHttpServletResponse();

		ProblemResponses.escribir(request, response, HttpStatus.UNAUTHORIZED,
				URI.create("https://akine.app/problems/unauthorized"), "titulo", "detalle");

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getContentAsString())
				.contains("\"type\":\"https://akine.app/problems/unauthorized\"")
				.contains("\"title\":\"titulo\"")
				.contains("\"status\":401")
				.contains("\"detail\":\"detalle\"")
				.contains("\"instance\":\"/api/v1/algo\"");
	}

	@Test
	@DisplayName("la instancia no incluye el context path: no ata el cuerpo al despliegue")
	void la_instancia_no_incluye_el_context_path() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/akine/api/v1/algo");
		request.setContextPath("/akine");
		MockHttpServletResponse response = new MockHttpServletResponse();

		ProblemResponses.escribir(request, response, HttpStatus.FORBIDDEN,
				ProblemResponses.FORBIDDEN, "t", "d");

		assertThat(response.getContentAsString()).contains("\"instance\":\"/api/v1/algo\"");
	}

	@Test
	@DisplayName("una ruta raiz vacia se normaliza a /")
	void una_ruta_vacia_se_normaliza() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/akine");
		request.setContextPath("/akine");

		assertThat(ProblemResponses.rutaDe(request)).isEqualTo("/");
	}

	@Test
	@DisplayName("no pisa una respuesta ya comprometida")
	void no_pisa_una_respuesta_comprometida() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/algo");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(201);
		response.getWriter().write("cuerpo previo");
		response.flushBuffer();

		ProblemResponses.escribir(request, response, HttpStatus.UNAUTHORIZED,
				ProblemResponses.UNAUTHORIZED, "t", "d");

		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getContentAsString()).isEqualTo("cuerpo previo");
	}

	@Test
	@DisplayName("los tres tipos de problema cuelgan del prefijo unico del proyecto")
	void los_tipos_cuelgan_del_prefijo_del_proyecto() {
		assertThat(ProblemResponses.UNAUTHORIZED.toString())
				.startsWith(ProblemResponses.BASE);
		assertThat(ProblemResponses.FORBIDDEN.toString()).startsWith(ProblemResponses.BASE);
		assertThat(ProblemResponses.RATE_LIMITED.toString()).startsWith(ProblemResponses.BASE);
	}
}
