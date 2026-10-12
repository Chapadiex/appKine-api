package com.akine.testsupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static com.akine.testsupport.SliceDeController.miembro;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Esqueleto de un slice {@code @WebMvcTest} que recorre la capa REST entera de un módulo.
 *
 * <p>La subclase declara dos fuentes estáticas: {@code operaciones()} —método, ruta, cuerpo y
 * status esperado de cada endpoint— y {@code excepciones()} —típicamente
 * {@link SliceDeController#excepcionesManejadas} sobre el advice del módulo—, y dice cómo provocar
 * una excepción desde un endpoint. Los servicios son {@link Fixture#mockQueResponde}: devuelven
 * vistas completas, así que cada operación recorre su mapeo a DTO campo por campo.
 *
 * <p>Lo que se afirma es el borde HTTP, que es lo que los ITs —que entran por el servicio— no ven:
 * el status de cada operación (201 con {@code Location}, 200, 204), que el cuerpo deserialice y pase
 * la validación, y que cada excepción del módulo salga como Problem Detail con un {@code type} de
 * AKINE y un status 4xx, nunca como 500.
 */
public abstract class SliceRestDeModulo {

	@Autowired protected MockMvc mockMvc;
	@Autowired protected JsonMapper json;

	/** Prefijo común a las rutas de {@code operaciones()}. */
	protected abstract String prefijo();

	/** Los mocks de servicio del slice: se les borran los stubs después de cada test. */
	protected abstract Object[] servicios();

	/** Hace que el endpoint de {@link #pedidoQueFalla()} lance {@code excepcion}. */
	protected abstract void provocar(Throwable excepcion);

	protected abstract MockHttpServletRequestBuilder pedidoQueFalla();

	/** Cabeceras que todo pedido del módulo necesita (p. ej. la justificación de acceso clínico). */
	protected MockHttpServletRequestBuilder decorar(MockHttpServletRequestBuilder pedido) {
		return pedido;
	}

	/**
	 * Si un 201 de esa ruta tiene que traer {@code Location}. Un recurso sin lectura individual no
	 * lo manda: un Location que responde 404 al seguirlo es peor que no mandarlo.
	 */
	protected boolean exigeLocation(String ruta) {
		return true;
	}

	@AfterEach
	void borrarStubs() {
		Mockito.reset(servicios());
	}

	protected static Arguments op(String metodo, String ruta, Object cuerpo, int status) {
		return Arguments.of(metodo, ruta, cuerpo, status);
	}

	protected String cuerpo(Object cuerpo) {
		return cuerpo instanceof String crudo ? crudo : json.writeValueAsString(
				cuerpo instanceof Class<?> tipo ? Fixture.de(tipo) : cuerpo);
	}

	protected ResultActions ejecutar(String metodo, String ruta, Object cuerpo) throws Exception {
		MockHttpServletRequestBuilder pedido = decorar(
				request(HttpMethod.valueOf(metodo), prefijo() + ruta).with(miembro()));
		if (cuerpo != null) {
			pedido.contentType(MediaType.APPLICATION_JSON).content(cuerpo(cuerpo));
		}
		return mockMvc.perform(pedido);
	}

	@ParameterizedTest(name = "{0} {1} -> {3}")
	@MethodSource("operaciones")
	@DisplayName("cada operacion responde su status con la vista completa mapeada")
	void cada_operacion(String metodo, String ruta, Object cuerpo, int esperado) throws Exception {
		ResultActions resultado = ejecutar(metodo, ruta, cuerpo).andExpect(status().is(esperado));
		if (esperado == 201 && exigeLocation(ruta)) {
			resultado.andExpect(header().exists("Location"));
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("excepciones")
	@DisplayName("cada excepcion del modulo sale como Problem Detail tipado, nunca como 500")
	void cada_excepcion_es_un_problem(Class<? extends Throwable> tipo) throws Exception {
		provocar(Fixture.instanciar(tipo));

		mockMvc.perform(decorar(pedidoQueFalla().with(miembro())))
				.andExpect(status().is4xxClientError())
				.andExpect(jsonPath("$.type").value(startsWith("https://akine.app/problems/")));
	}
}
