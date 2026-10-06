package com.akine.encounter.api;

import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.application.TratamientoView;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.TipoDatoParametro;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los tratamientos realizados (AKINE-06.04, RF-M14-005).
 *
 * <p>Lo que solo se rompe en esta capa: que una lista de parametros omitida llegue como lista
 * vacia y no como {@code null} —el servicio la recorre—, que los parametros conserven su tipo, y
 * que la baja conteste 204 aunque el cliente pida solo {@code application/problem+json}.
 */
@WebMvcTest(TratamientoController.class)
@Import({TratamientoControllerTest.SliceSecurityConfig.class, EncounterApiActor.class})
class TratamientoControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/sesiones/501/tratamientos";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TratamientoService tratamientoService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("Registrar responde 201 con Location, y sin parametros llega una lista vacia")
	void registrar_sin_parametros() throws Exception {
		given(tratamientoService.registrar(any(), anyLong(), anyLong(), any(), anyLong()))
				.willReturn(vista(List.of()));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"practicaId\":610,\"zona\":\"Lumbar\",\"lateralidad\":\"DERECHA\","
								+ "\"duracionMinutos\":20,\"version\":3}")
						.with(miembro(8L)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location",
						"/api/v1/consultorios/7/sesiones/501/tratamientos/900"))
				.andExpect(jsonPath("$.orden").value(1))
				.andExpect(jsonPath("$.sesionVersion").value(4));

		ArgumentCaptor<TratamientoAplicado> aplicado =
				ArgumentCaptor.forClass(TratamientoAplicado.class);
		verify(tratamientoService).registrar(any(), eq(7L), eq(501L), aplicado.capture(), eq(3L));
		assertThat(aplicado.getValue().practicaId()).isEqualTo(610L);
		assertThat(aplicado.getValue().lateralidad()).isEqualTo(Lateralidad.DERECHA);
		assertThat(aplicado.getValue().parametros()).isNotNull().isEmpty();
	}

	@Test
	@DisplayName("Al reemplazar, los parametros viajan con su tipo y su unidad")
	void reemplazar_con_parametros() throws Exception {
		given(tratamientoService.reemplazar(any(), anyLong(), anyLong(), anyLong(), any(), anyLong()))
				.willReturn(vista(List.of(new TratamientoView.ParametroView(
						"intensidad", TipoDatoParametro.NUMERICO, new BigDecimal("12.5"), null, null,
						"mA", 0))));

		mockMvc.perform(put(RUTA + "/900")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"practicaId":610,"version":4,"parametros":[
								  {"clave":"intensidad","tipoDato":"NUMERICO","valorNumerico":12.5,"unidad":"mA"}]}""")
						.with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.parametros[0].clave").value("intensidad"))
				.andExpect(jsonPath("$.parametros[0].unidad").value("mA"));

		ArgumentCaptor<TratamientoAplicado> aplicado =
				ArgumentCaptor.forClass(TratamientoAplicado.class);
		verify(tratamientoService).reemplazar(
				any(), eq(7L), eq(501L), eq(900L), aplicado.capture(), eq(4L));
		assertThat(aplicado.getValue().parametros()).singleElement().satisfies(parametro -> {
			assertThat(parametro.tipoDato()).isEqualTo(TipoDatoParametro.NUMERICO);
			assertThat(parametro.valorNumerico()).isEqualByComparingTo("12.5");
			assertThat(parametro.unidad()).isEqualTo("mA");
		});
	}

	@Test
	@DisplayName("Un parametro sin tipo es 400 en la validacion: el caso borde de la etapa")
	void parametro_sin_tipo_es_400() throws Exception {
		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"practicaId\":610,\"version\":3,"
								+ "\"parametros\":[{\"clave\":\"intensidad\",\"valorNumerico\":12}]}")
						.with(miembro(8L)))
				.andExpect(status().isBadRequest());

		verify(tratamientoService, never()).registrar(any(), anyLong(), anyLong(), any(), anyLong());
	}

	@Test
	@DisplayName("La baja responde 204 aunque el cliente acepte SOLO problem+json")
	void la_baja_responde_204_con_accept_problem_json() throws Exception {
		mockMvc.perform(delete(RUTA + "/900")
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.content("{\"motivo\":\"El paciente refirio molestia\",\"version\":4}")
						.with(miembro(8L)))
				.andExpect(status().isNoContent());

		verify(tratamientoService).darDeBaja(
				any(), eq(7L), eq(501L), eq(900L), eq("El paciente refirio molestia"), eq(4L));
	}

	@Test
	@DisplayName("El listado publica las intervenciones vigentes con sus parametros")
	void el_listado() throws Exception {
		given(tratamientoService.listar(any(), anyLong(), anyLong()))
				.willReturn(List.of(vista(List.of(new TratamientoView.ParametroView(
						"con_calor", TipoDatoParametro.BOOLEANO, null, null, Boolean.TRUE, null, 0)))));

		mockMvc.perform(get(RUTA).with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].practicaCodigo").value("P-610"))
				.andExpect(jsonPath("$[0].parametros[0].valorBooleano").value(true));
	}

	private static TratamientoView vista(List<TratamientoView.ParametroView> parametros) {
		return new TratamientoView(900L, 501L, 1, 610L, "P-610", "Electroterapia", null, "Lumbar",
				"DERECHA", 20, 31L, null, null, null, Instant.parse("2026-09-20T13:44:10Z"), true,
				parametros, 4L);
	}

	private static RequestPostProcessor miembro(long accountId) {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(accountId), "n/a", List.of()));
	}

	private record PrincipalDePrueba(long accountId) implements AuthenticatedPrincipal {

		@Override
		public boolean platformAdmin() {
			return false;
		}

		@Override
		public Long organizationId() {
			return null;
		}

		@Override
		public Long consultorioId() {
			return null;
		}
	}

	/** Cadena minima para el slice: {@code permitAll}, la autorizacion la decide el servicio. */
	@TestConfiguration
	@EnableWebSecurity
	static class SliceSecurityConfig {

		@Bean
		SecurityFilterChain sliceSecurityFilterChain(HttpSecurity http) throws Exception {
			http
					.csrf(csrf -> csrf.disable())
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}
}
