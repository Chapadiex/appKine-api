package com.akine.encounter.api;

import com.akine.encounter.application.ComparacionDeMedicionesView;
import com.akine.encounter.application.MedicionComparadaView;
import com.akine.encounter.application.MedicionService;
import com.akine.encounter.application.MedicionView;
import com.akine.encounter.application.MedicionesDeSesionView;
import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.ValorMedido;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.resource.spi.MedicionTipo;
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
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del examen fisico de una sesion (RF-M14-004).
 *
 * <p>Cubre lo que solo se rompe en esta capa y que ningun test del servicio veria: que la
 * lateralidad omitida llegue como {@code NO_APLICA} y no como {@code null}, que el {@code DELETE}
 * conteste 204 <b>aunque el cliente pida {@code application/problem+json}</b> —el 406 que ya
 * rompio dos flujos de identidad—, y que una comparacion sin baseline sea un 200 y no un error.
 */
@WebMvcTest(SesionMedicionController.class)
@Import({SesionMedicionControllerTest.SliceSecurityConfig.class, EncounterApiActor.class})
class SesionMedicionControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/sesiones/501/mediciones";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MedicionService medicionService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("La lateralidad omitida llega al servicio como NO_APLICA, jamas como null: es "
			+ "parte de la identidad de la medicion y entra en el unique")
	void lateralidad_omitida_llega_como_no_aplica() throws Exception {
		given(medicionService.registrar(
				any(), anyLong(), anyLong(), anyLong(), any(), any(), any()))
				.willReturn(medicion(LateralidadMedicion.NO_APLICA, new BigDecimal("6")));

		mockMvc.perform(put(RUTA + "/12")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"valorNumerico\":6}")
						.with(miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lateralidad").value("NO_APLICA"));

		verify(medicionService).registrar(
				any(), eq(7L), eq(501L), eq(12L),
				eq(LateralidadMedicion.NO_APLICA), any(), eq(null));
	}

	@Test
	@DisplayName("El valor viaja intacto al servicio: la capa api no decide que columna "
			+ "corresponde, porque eso depende del tipo de la definicion")
	void el_valor_viaja_intacto_al_servicio() throws Exception {
		given(medicionService.registrar(
				any(), anyLong(), anyLong(), anyLong(), any(), any(), any()))
				.willReturn(medicion(LateralidadMedicion.IZQUIERDA, new BigDecimal("92.5")));

		mockMvc.perform(put(RUTA + "/12?lateralidad=IZQUIERDA")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"valorNumerico\":92.5,\"nota\":\"con dolor\"}")
						.with(miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unidad").value("grados"))
				.andExpect(jsonPath("$.valorNumerico").value(92.5));

		ArgumentCaptor<ValorMedido> valor = ArgumentCaptor.forClass(ValorMedido.class);
		verify(medicionService).registrar(
				any(), eq(7L), eq(501L), eq(12L), eq(LateralidadMedicion.IZQUIERDA),
				valor.capture(), eq("con dolor"));

		assertThat(valor.getValue().numerico()).isEqualByComparingTo("92.5");
		assertThat(valor.getValue().texto()).isNull();
		assertThat(valor.getValue().booleano()).isNull();
	}

	@Test
	@DisplayName("El DELETE contesta 204 aunque el cliente acepte SOLO problem+json: sin ese "
			+ "produces el request muere con 406 antes de entrar al metodo")
	void el_delete_responde_204_con_accept_problem_json() throws Exception {
		mockMvc.perform(delete(RUTA + "/12?lateralidad=DERECHA")
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.with(miembro(7L)))
				.andExpect(status().isNoContent());

		verify(medicionService).borrar(
				any(), eq(7L), eq(501L), eq(12L), eq(LateralidadMedicion.DERECHA));
	}

	@Test
	@DisplayName("Una comparacion sin baseline es 200 y no un error: sesionAnteriorId ausente y "
			+ "anterior ausente son una respuesta valida")
	void sin_baseline_la_comparacion_responde_200() throws Exception {
		given(medicionService.comparar(any(), anyLong(), anyLong()))
				.willReturn(new ComparacionDeMedicionesView(null, false, List.of(
						MedicionComparadaView.de(
								medicion(LateralidadMedicion.IZQUIERDA, new BigDecimal("92.5")),
								null))));

		mockMvc.perform(get(RUTA + "/comparacion").with(miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sesionAnteriorId").doesNotExist())
				.andExpect(jsonPath("$.acotadaAlCaso").value(false))
				.andExpect(jsonPath("$.medidas[0].anterior").doesNotExist())
				.andExpect(jsonPath("$.medidas[0].delta").doesNotExist());
	}

	@Test
	@DisplayName("El listado informa la completitud y no la gatea: completo viaja con sus dos "
			+ "cuentas al lado para poder decir 1 de 18")
	void el_listado_informa_la_completitud() throws Exception {
		given(medicionService.listar(any(), anyLong(), anyLong()))
				.willReturn(MedicionesDeSesionView.de(
						List.of(medicion(LateralidadMedicion.IZQUIERDA, new BigDecimal("92.5"))),
						18));

		mockMvc.perform(get(RUTA).with(miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mediciones[0].codigo").value("ROM_RODILLA_FLEX"))
				.andExpect(jsonPath("$.definicionesCubiertas").value(1))
				.andExpect(jsonPath("$.definicionesDisponibles").value(18))
				.andExpect(jsonPath("$.completo").value(false));
	}

	private static MedicionView medicion(LateralidadMedicion lateralidad, BigDecimal valor) {
		return new MedicionView(
				900L, 12L, "ROM_RODILLA_FLEX", "ROM de rodilla en flexion",
				MedicionTipo.NUMERICO, "grados", 2L, lateralidad,
				valor, null, null, null, Instant.parse("2026-09-20T13:44:10Z"), 1L);
	}

	/** Cuenta autenticada comun: lo que importa es el TIPO del principal, no sus privilegios. */
	private static RequestPostProcessor miembro(long accountId) {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(accountId), "n/a", List.of()));
	}

	/**
	 * Los dos ids van nulos a proposito: son un hint del token y {@code EncounterApiActor} no los
	 * mira —toma el contexto del {@code TenantContextHolder}, que es lo unico revalidado contra
	 * la base (RN-M01-003)—.
	 */
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

	/**
	 * Cadena minima para el slice: {@code permitAll}, para que la decision de "quien puede hacer
	 * que" quede donde el modulo la puso y sea eso lo que se verifica, y no la cadena global.
	 */
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
