package com.akine.scheduling.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.CicloDeRecepcionService.ResultadoDeLlegada;
import com.akine.scheduling.application.EventoDeRecepcionView;
import com.akine.scheduling.application.PrepagoView;
import com.akine.scheduling.application.RecepcionView;
import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la recepcion (M13, AKINE E-4): 201 contra 200 del check-in idempotente, que los
 * cuerpos lleguen al servicio campo por campo, que Particular sin motivo muera en la validacion
 * sin tocar el servicio, y el problem type propio de la maquina de la recepcion.
 */
@WebMvcTest(RecepcionController.class)
@Import({RecepcionControllerTest.SliceSecurityConfig.class, SchedulingApiActor.class})
class RecepcionControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/turnos/301/recepcion";

	@Autowired private MockMvc mockMvc;
	@MockitoBean private CicloDeRecepcionService recepcion;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("el check-in que abre la recepcion responde 201 con Location; el repetido, 200")
	void check_in_201_y_200() throws Exception {
		given(recepcion.registrarLlegada(any(), eq(7L), eq(301L)))
				.willReturn(new ResultadoDeLlegada(vista("LLEGO"), true, null))
				.willReturn(new ResultadoDeLlegada(vista("LLEGO"), false, null));

		mockMvc.perform(post(RUTA).with(miembro()))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", RUTA))
				.andExpect(jsonPath("$.estado").value("LLEGO"))
				.andExpect(jsonPath("$.turnoId").value(301));
		mockMvc.perform(post(RUTA).with(miembro()))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("validar pasa la cobertura elegida y la version; sin cobertura llega null")
	void validar() throws Exception {
		given(recepcion.validar(any(), anyLong(), anyLong(), any(), anyLong())).willReturn(vista("OBSERVADA"));

		mockMvc.perform(post(RUTA + "/validacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"coberturaId\":412,\"expectedVersion\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("OBSERVADA"));
		mockMvc.perform(post(RUTA + "/validacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":4}"))
				.andExpect(status().isOk());

		verify(recepcion).validar(any(), eq(7L), eq(301L), eq(412L), eq(3L));
		verify(recepcion).validar(any(), eq(7L), eq(301L), isNull(), eq(4L));
	}

	@Test
	@DisplayName("Particular sin motivo es 400 y no llega al servicio")
	void particular_sin_motivo() throws Exception {
		mockMvc.perform(post(RUTA + "/particular").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"motivo\":\"  \",\"expectedVersion\":1}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(recepcion);
	}

	@Test
	@DisplayName("Particular, espera, llamado y anulacion llegan al servicio con su version")
	void transiciones() throws Exception {
		given(recepcion.atenderComoParticular(any(), anyLong(), anyLong(), any(), anyLong()))
				.willReturn(vista("VALIDADA"));
		given(recepcion.pasarAEspera(any(), anyLong(), anyLong(), anyLong())).willReturn(vista("EN_ESPERA"));
		given(recepcion.llamar(any(), anyLong(), anyLong(), anyLong())).willReturn(vista("LLAMADA"));
		given(recepcion.anular(any(), anyLong(), anyLong(), any(), any())).willReturn(vista("ANULADA"));

		mockMvc.perform(post(RUTA + "/particular").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"motivo\":\"Abona en el mostrador\",\"expectedVersion\":1}"))
				.andExpect(jsonPath("$.estado").value("VALIDADA"));
		mockMvc.perform(post(RUTA + "/espera").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
				.andExpect(jsonPath("$.estado").value("EN_ESPERA"));
		mockMvc.perform(post(RUTA + "/llamado").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
				.andExpect(jsonPath("$.estado").value("LLAMADA"));
		mockMvc.perform(post(RUTA + "/anulacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
				.andExpect(jsonPath("$.estado").value("ANULADA"));

		verify(recepcion).atenderComoParticular(any(), eq(7L), eq(301L), eq("Abona en el mostrador"), eq(1L));
		verify(recepcion).pasarAEspera(any(), eq(7L), eq(301L), eq(2L));
		verify(recepcion).llamar(any(), eq(7L), eq(301L), eq(3L));
		verify(recepcion).anular(any(), eq(7L), eq(301L), isNull(), eq(4L));
	}

	@Test
	@DisplayName("una transicion imposible es 409 con el problem type de la recepcion y su motivo")
	void transicion_no_permitida() throws Exception {
		given(recepcion.pasarAEspera(any(), anyLong(), anyLong(), anyLong()))
				.willThrow(new TransicionDeRecepcionNoPermitidaException(301L, "falta la validacion"));

		mockMvc.perform(post(RUTA + "/espera").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/recepcion-transicion-no-permitida"))
				.andExpect(jsonPath("$.motivo").value("falta la validacion"));
	}

	@Test
	@DisplayName("ver e historial")
	void lecturas() throws Exception {
		given(recepcion.ver(any(), eq(7L), eq(301L))).willReturn(vista("EN_ESPERA"));
		given(recepcion.historial(any(), eq(7L), eq(301L))).willReturn(List.of(
				new EventoDeRecepcionView(1L, 77L, "LLEGADA", null, "LLEGO", null, 12L, Instant.now())));

		mockMvc.perform(get(RUTA).with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("EN_ESPERA"));
		mockMvc.perform(get(RUTA + "/historial").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].tipo").value("LLEGADA"))
				.andExpect(jsonPath("$[0].estadoAnterior").doesNotExist());
	}

	private static RecepcionView vista(String estado) {
		return new RecepcionView(77L, 301L, estado, Instant.now(), 12L, null, null, null, null,
				null, null, null, null, null, null, null, 0L,
				new PrepagoView(PrepagoView.PENDIENTE, new java.math.BigDecimal("8500.00"), "ARS",
						null, null, null));
	}

	private static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(8L), "n/a", List.of()));
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
