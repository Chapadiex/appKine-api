package com.akine.platform.infrastructure.observability;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El id de correlacion de G-4: que vuelva en la respuesta, que viva en el MDC mientras dura el
 * request y que no sobreviva al request. Unitario: la integracion con la cadena real la cubre
 * {@code ObservabilidadIT}.
 */
class CorrelationIdFilterTest {

	private final CorrelationIdFilter filtro = new CorrelationIdFilter();
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final List<String> vistoEnElMdc = new ArrayList<>();
	private final FilterChain cadena = (req, res) -> vistoEnElMdc.add(MDC.get(ClavesDeMdc.REQUEST_ID));

	@AfterEach
	void limpiar() {
		MDC.clear();
	}

	@Test
	@DisplayName("un X-Request-Id valido se respeta: vuelve en la respuesta y esta en el MDC")
	void respeta_el_id_entrante() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/version");
		request.addHeader(CorrelationIdFilter.HEADER, "front-7f3a.b2:9");

		filtro.doFilter(request, response, cadena);

		assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("front-7f3a.b2:9");
		assertThat(vistoEnElMdc).containsExactly("front-7f3a.b2:9");
	}

	@Test
	@DisplayName("sin X-Request-Id se genera uno, y el mismo va al MDC y a la respuesta")
	void genera_uno_si_no_viene() throws Exception {
		filtro.doFilter(new MockHttpServletRequest("GET", "/api/v1/version"), response, cadena);

		String devuelto = response.getHeader(CorrelationIdFilter.HEADER);
		assertThat(devuelto).matches("[0-9a-f-]{36}");
		assertThat(vistoEnElMdc).containsExactly(devuelto);
	}

	@Test
	@DisplayName("un X-Request-Id con salto de linea o demasiado largo se descarta: no entra al log")
	void descarta_un_id_que_inyectaria_lineas_de_log() throws Exception {
		MockHttpServletRequest inyeccion = new MockHttpServletRequest("GET", "/api/v1/version");
		inyeccion.addHeader(CorrelationIdFilter.HEADER, "abc\n{\"level\":\"ERROR\"}");
		filtro.doFilter(inyeccion, response, cadena);

		MockHttpServletRequest largo = new MockHttpServletRequest("GET", "/api/v1/version");
		largo.addHeader(CorrelationIdFilter.HEADER, "a".repeat(65));
		MockHttpServletResponse otraRespuesta = new MockHttpServletResponse();
		filtro.doFilter(largo, otraRespuesta, cadena);

		assertThat(vistoEnElMdc).hasSize(2).allMatch(id -> id.matches("[0-9a-f-]{36}"));
		assertThat(response.getHeader(CorrelationIdFilter.HEADER)).doesNotContain("\n");
	}

	@Test
	@DisplayName("al terminar el request el MDC queda limpio: el hilo no se lleva el id")
	void limpia_el_mdc_al_terminar() throws Exception {
		filtro.doFilter(new MockHttpServletRequest("GET", "/api/v1/version"), response, cadena);

		assertThat(MDC.get(ClavesDeMdc.REQUEST_ID)).isNull();
	}
}
