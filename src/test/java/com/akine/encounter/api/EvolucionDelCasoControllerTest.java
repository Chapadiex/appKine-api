package com.akine.encounter.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.encounter.application.EvolucionDelCasoService;
import com.akine.encounter.application.EvolucionDelCasoView;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion;
import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo.Severidad;
import com.akine.encounter.domain.exception.EvolucionDeshabilitadaException;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** M31: la respuesta HTTP de la evolucion, el header de justificacion y el 404 con la funcion apagada. */
@WebMvcTest(EvolucionDelCasoController.class)
@Import({EvolucionDelCasoControllerTest.SliceSecurityConfig.class, EncounterApiActor.class,
		EncounterProblemHandler.class})
class EvolucionDelCasoControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/casos/41/evolucion";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EvolucionDelCasoService evolucion;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	void devuelveIndicadoresAlertasYPlanYPasaLaJustificacion() throws Exception {
		given(evolucion.analizar(any(), eq(7L), eq(41L), eq("Interconsulta"))).willReturn(vista());

		mockMvc.perform(get(RUTA).header("X-Justificacion-Acceso", "Interconsulta").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.casoId").value(41))
				.andExpect(jsonPath("$.sesionesAnalizadas").value(4))
				.andExpect(jsonPath("$.hallazgos[0].codigo").value("DOLOR_SIN_MEJORA"))
				.andExpect(jsonPath("$.hallazgos[0].severidad").value("ATENCION"))
				.andExpect(jsonPath("$.hallazgos[0].sesiones[1]").value(102))
				.andExpect(jsonPath("$.indicadores.dolor.diferencia").value(-1))
				.andExpect(jsonPath("$.indicadores.dolor.puntos[0].zona").value("rodilla"))
				.andExpect(jsonPath("$.indicadores.evolucion.porValor.IGUAL").value(2))
				.andExpect(jsonPath("$.indicadores.mediciones[0].mejoraCuando").value("SUBE"))
				.andExpect(jsonPath("$.indicadores.mediciones[0].serie.ultimo").value(95))
				.andExpect(jsonPath("$.indicadores.adherencia.porcentaje").value(25))
				.andExpect(jsonPath("$.indicadores.frecuencia.planificadaPorSemana").value(3))
				.andExpect(jsonPath("$.indicadores.tratamientos[0].practica").value("Fisioterapia"))
				.andExpect(jsonPath("$.plan.items[0].realizadas").value(4))
				.andExpect(jsonPath("$.advertencias[0]").value("1 sesión sin caso."));
	}

	@Test
	void sinPlanNoMandaElCampo() throws Exception {
		EvolucionDelCasoView conPlan = vista();
		given(evolucion.analizar(any(), eq(7L), eq(41L), eq(null))).willReturn(new EvolucionDelCasoView(
				conPlan.casoId(), conPlan.numeroCaso(), conPlan.estadoCaso(), conPlan.diagnosticoPresuntivo(),
				conPlan.objetivoTerapeutico(), conPlan.analisis(), conPlan.hallazgos(), null, List.of()));

		mockMvc.perform(get(RUTA).with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plan").doesNotExist());
	}

	@Test
	void conLaFuncionApagadaResponde404() throws Exception {
		given(evolucion.analizar(any(), eq(7L), eq(41L), eq(null)))
				.willThrow(new EvolucionDeshabilitadaException());

		mockMvc.perform(get(RUTA).with(miembro())).andExpect(status().isNotFound());
	}

	private static EvolucionDelCasoView vista() {
		LocalDate dia = LocalDate.of(2026, 11, 1);
		AnalisisDeEvolucion.Serie dolor = new AnalisisDeEvolucion.Serie(
				List.of(new AnalisisDeEvolucion.Punto(101L, dia, new BigDecimal("7"), "rodilla"),
						new AnalisisDeEvolucion.Punto(102L, dia.plusWeeks(1), new BigDecimal("6"), "rodilla")),
				new BigDecimal("7"), new BigDecimal("6"), new BigDecimal("-1"));
		AnalisisDeEvolucion.SerieDeMedicion rom = new AnalisisDeEvolucion.SerieDeMedicion(
				50L, "ROM", "ROM de rodilla", "grados", "DERECHA", "SUBE", false,
				new AnalisisDeEvolucion.Serie(
						List.of(new AnalisisDeEvolucion.Punto(101L, dia, new BigDecimal("90"), null)),
						new BigDecimal("90"), new BigDecimal("95"), new BigDecimal("5")));
		AnalisisDeEvolucion analisis = new AnalisisDeEvolucion(4, 4, dia, dia.plusWeeks(3),
				new AnalisisDeEvolucion.Indicadores(
						dolor,
						new AnalisisDeEvolucion.Conteo(Map.of("IGUAL", 2), List.of("IGUAL", "IGUAL")),
						new AnalisisDeEvolucion.Conteo(Map.of("BUENA", 4), List.of("BUENA")),
						List.of(rom),
						new AnalisisDeEvolucion.Adherencia(4, 1, 1, 0, 0, 25),
						new AnalisisDeEvolucion.Frecuencia(4, new BigDecimal("1.0"), 3),
						List.of(new AnalisisDeEvolucion.TratamientoResumido(9L, "Fisioterapia", 4, dia))),
				List.of());
		PlanVigenteSnapshot plan = new PlanVigenteSnapshot(90L, "ACTIVO", Instant.parse("2026-10-01T12:00:00Z"),
				2, Instant.parse("2026-10-10T12:00:00Z"), "Recuperar la flexion", 3, 8,
				List.of(new PlanVigenteSnapshot.ItemDelPlan(5L, "Kinesiologia", 10, 4, 1, false)), false);
		return new EvolucionDelCasoView(41L, 1, "ACTIVO", "Gonalgia", "Volver a correr", analisis,
				List.of(new EvolucionDelCasoView.HallazgoView(CodigoDeHallazgo.DOLOR_SIN_MEJORA,
						Severidad.ATENCION, "El dolor paso de 7 a 6.", List.of(101L, 102L), List.of())),
				plan, List.of("1 sesión sin caso."));
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
			// Sin desactivar CSRF: el slice solo hace GET, que no lo exige.
			http
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}
}
