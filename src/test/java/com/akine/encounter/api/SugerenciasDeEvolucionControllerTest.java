package com.akine.encounter.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.akine.encounter.application.SugerenciasDeEvolucionService;
import com.akine.encounter.application.SugerenciasView;
import com.akine.encounter.domain.SugerenciaEvolucion;
import com.akine.encounter.domain.exception.GeneracionEnCursoException;
import com.akine.encounter.domain.exception.ModeloDeEvolucionNoDisponibleException;
import com.akine.encounter.domain.exception.ModeloDeEvolucionOcupadoException;
import com.akine.encounter.domain.exception.SugerenciaYaRespondidaException;
import com.akine.encounter.spi.CitaGuardada;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M31 fase 2: la capa HTTP de las sugerencias. Las tres operaciones, la cabecera de justificacion,
 * los problemas tipados del modelo y del candado, y el 400 del {@code @Pattern} de la respuesta.
 */
@WebMvcTest(SugerenciasDeEvolucionController.class)
@Import({SugerenciasDeEvolucionControllerTest.SliceSecurityConfig.class, EncounterApiActor.class,
		EncounterProblemHandler.class})
class SugerenciasDeEvolucionControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/casos/41/evolucion/sugerencias";
	private static final String JUSTIFICACION = "X-Justificacion-Acceso";
	private static final UUID GENERACION = UUID.fromString("3f2b8c1e-5a6d-4e7f-9a0b-1c2d3e4f5a6b");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SugerenciasDeEvolucionService servicio;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	void generarDevuelveElResultadoYPasaLaJustificacion() throws Exception {
		given(servicio.generar(any(), eq(7L), eq(41L), eq("Interconsulta"))).willReturn(new SugerenciasView(
				GENERACION, SugerenciasView.Resultado.SUGERENCIAS, List.of(sugerencia("PENDIENTE", null)), 1,
				List.of("TEXTO_OMITIDO")));

		mockMvc.perform(post(RUTA).header(JUSTIFICACION, "Interconsulta").with(miembro()).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.generacionId").value(GENERACION.toString()))
				.andExpect(jsonPath("$.resultado").value("SUGERENCIAS"))
				.andExpect(jsonPath("$.descartadas").value(1))
				.andExpect(jsonPath("$.advertencias[0]").value("TEXTO_OMITIDO"))
				.andExpect(jsonPath("$.sugerencias[0].id").value(301))
				.andExpect(jsonPath("$.sugerencias[0].hallazgo").value("DOLOR_SIN_MEJORA"))
				.andExpect(jsonPath("$.sugerencias[0].citas[0].libroId").value(5))
				.andExpect(jsonPath("$.sugerencias[0].respuesta").value("PENDIENTE"));
	}

	@Test
	void listarDevuelveLaPaginaYPasaLaJustificacion() throws Exception {
		given(servicio.listar(any(), eq(7L), eq(41L), eq("Auditoria"), eq(PageRequest.of(0, 20))))
				.willReturn(new PageImpl<>(List.of(sugerencia("ACEPTADA", null)), PageRequest.of(0, 20), 1));

		mockMvc.perform(get(RUTA).header(JUSTIFICACION, "Auditoria").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(301))
				.andExpect(jsonPath("$.content[0].respuesta").value("ACEPTADA"))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void responderPasaRespuestaMotivoVersionYJustificacion() throws Exception {
		given(servicio.responder(any(), eq(7L), eq(41L), eq(301L), eq(SugerenciaEvolucion.Respuesta.DESCARTADA),
				eq("No aplica"), eq(2L), eq("Interconsulta"))).willReturn(sugerencia("DESCARTADA", "No aplica"));

		mockMvc.perform(post(RUTA + "/301/respuesta").header(JUSTIFICACION, "Interconsulta")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"respuesta\":\"DESCARTADA\",\"motivo\":\"No aplica\",\"version\":2}")
						.with(miembro()).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.respuesta").value("DESCARTADA"))
				.andExpect(jsonPath("$.motivoDescarte").value("No aplica"));
	}

	@Test
	void conUnPedidoEnCursoResponde429() throws Exception {
		given(servicio.generar(any(), anyLong(), anyLong(), any())).willThrow(new GeneracionEnCursoException());

		mockMvc.perform(post(RUTA).with(miembro()).with(csrf()))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("generacion-en-curso")));
	}

	@Test
	void conElCupoLlenoResponde503ModeloOcupado() throws Exception {
		given(servicio.generar(any(), anyLong(), anyLong(), any()))
				.willThrow(new ModeloDeEvolucionOcupadoException(new RuntimeException("cupo")));

		mockMvc.perform(post(RUTA).with(miembro()).with(csrf()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("modelo-ocupado")));
	}

	@Test
	void sinRespuestaDelModeloResponde503ModeloNoDisponible() throws Exception {
		given(servicio.generar(any(), anyLong(), anyLong(), any()))
				.willThrow(new ModeloDeEvolucionNoDisponibleException(new RuntimeException("plazo")));

		mockMvc.perform(post(RUTA).with(miembro()).with(csrf()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("modelo-no-disponible")));
	}

	@Test
	void yaRespondidaResponde409() throws Exception {
		given(servicio.responder(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), any()))
				.willThrow(new SugerenciaYaRespondidaException(301L));

		mockMvc.perform(post(RUTA + "/301/respuesta").contentType(MediaType.APPLICATION_JSON)
						.content("{\"respuesta\":\"ACEPTADA\",\"version\":0}")
						.with(miembro()).with(csrf()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("sugerencia-ya-respondida")))
				.andExpect(jsonPath("$.sugerenciaId").value(301));
	}

	@Test
	void unaRespuestaFueraDelVocabularioResponde400SinLlegarAlServicio() throws Exception {
		mockMvc.perform(post(RUTA + "/301/respuesta").contentType(MediaType.APPLICATION_JSON)
						.content("{\"respuesta\":\"PENDIENTE\",\"version\":0}")
						.with(miembro()).with(csrf()))
				.andExpect(status().isBadRequest());

		verify(servicio, never()).responder(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), any());
	}

	private static SugerenciasView.Sugerencia sugerencia(String respuesta, String motivo) {
		boolean pendiente = "PENDIENTE".equals(respuesta);
		return new SugerenciasView.Sugerencia(301L, GENERACION, "DOLOR_SIN_MEJORA",
				"Revisar la progresion de la carga.", "El dolor no bajo en las ultimas sesiones.",
				List.of(new CitaGuardada(5L, "Manual de rehabilitacion", 12, 13, 0.81, "ab".repeat(32))),
				List.of(101L, 102L), "qwen3:4b-instruct", respuesta, 11L, Instant.parse("2026-11-15T12:00:00Z"),
				pendiente ? null : 11L, pendiente ? null : Instant.parse("2026-11-15T12:05:00Z"), motivo, 2L);
	}

	private static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(new Principal(), "n/a", List.of()));
	}

	private record Principal() implements AuthenticatedPrincipal {

		@Override
		public long accountId() {
			return 11L;
		}

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
			// CSRF queda activo (registro de la fase 1, fila 32): los POST mandan el token con csrf().
			http
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}
}
