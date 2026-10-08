package com.akine.reporting.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.reporting.application.ExportadorCsv;
import com.akine.reporting.application.OperatingActor;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.domain.exception.ConsultorioNoAccesibleException;
import com.akine.reporting.domain.exception.RangoDeReporteInvalidoException;
import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los reportes (M23).
 *
 * <p>{@code ReporteReadIT} entra por el servicio, asi que nada ejercitaba esta capa: el mapeo de
 * la vista a la respuesta —que las omitidas y las advertencias lleguen, que el valor viaje como
 * decimal exacto—, que el CSV salga con el nombre de archivo y el charset, y que los dos errores
 * propios del modulo sean 400 y 404 con su {@code problemType}, no el 500 del handler global.
 */
@WebMvcTest(ReporteController.class)
@Import({
		ReporteControllerTest.SliceSecurityConfig.class,
		ReportingApiActor.class,
		ReportingProblemHandler.class,
		ExportadorCsv.class})
class ReporteControllerTest {

	private static final String RUTA = "/api/v1/consultorios/7/reportes";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ReporteService reporteService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("El reporte publica secciones, omitidas y advertencias, con el actor del token")
	void generar_mapea_la_vista_entera() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
		given(reporteService.generar(any(), anyLong(), any(), any(), any())).willReturn(vista());

		mockMvc.perform(get(RUTA + "/ECONOMICO")
						.param("desde", "2026-09-01").param("hasta", "2026-09-30")
						.with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reporte").value("ECONOMICO"))
				.andExpect(jsonPath("$.zona").value("America/Argentina/Cordoba"))
				.andExpect(jsonPath("$.secciones[0].indicadores[0].tipo").value("DINERO"))
				.andExpect(jsonPath("$.secciones[0].indicadores[0].valor").value(1500.50))
				.andExpect(jsonPath("$.secciones[0].indicadores[0].moneda").value("ARS"))
				.andExpect(jsonPath("$.secciones[0].columnas[0]").value("Dia"))
				.andExpect(jsonPath("$.secciones[0].filas[0][1]").value("3"))
				.andExpect(jsonPath("$.omitidas[0].permisoRequerido").value("hc:read"))
				.andExpect(jsonPath("$.advertencias[0].codigo")
						.value("sin-devengado-de-financiador"));

		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		verify(reporteService).generar(actor.capture(), eq(7L), eq(ReporteCode.ECONOMICO),
				eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)));
		assertThat(actor.getValue().accountId()).isEqualTo(8L);
		// Sin contexto de trabajo el actor viaja sin tenant: el servicio es quien corta con 403.
		assertThat(actor.getValue().contextOrganizationId()).isNull();
	}

	@Test
	@DisplayName("El catalogo lista cada reporte con sus secciones y si son clinicas")
	void catalogo() throws Exception {
		Map<ReporteCode, List<ReporteService.SeccionDisponible>> catalogo = new LinkedHashMap<>();
		catalogo.put(ReporteCode.CLINICO, List.of(
				new ReporteService.SeccionDisponible("casos", "Casos", "hc:read", true)));
		catalogo.put(ReporteCode.TURNOS, List.of());
		given(reporteService.catalogo(any(), eq(7L))).willReturn(catalogo);

		mockMvc.perform(get(RUTA).with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reportes[0].reporte").value("CLINICO"))
				.andExpect(jsonPath("$.reportes[0].secciones[0].permisoRequerido").value("hc:read"))
				.andExpect(jsonPath("$.reportes[0].secciones[0].clinica").value(true))
				.andExpect(jsonPath("$.reportes[1].secciones").isEmpty());
	}

	@Test
	@DisplayName("El export sale como adjunto CSV en UTF-8, con fuente, omitidas y advertencias")
	void export_csv() throws Exception {
		given(reporteService.generar(any(), anyLong(), any(), any(), any())).willReturn(vista());

		String csv = mockMvc.perform(get(RUTA + "/ECONOMICO/export")
						.param("desde", "2026-09-01").param("hasta", "2026-09-30")
						.with(miembro(8L)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition",
						"attachment; filename=\"reporte-economico-2026-09-01_2026-09-30.csv\""))
				.andExpect(header().string("Content-Type", containsString("text/csv")))
				.andExpect(header().string("Content-Type", containsString("UTF-8")))
				.andReturn().getResponse().getContentAsString();

		assertThat(csv)
				.contains("\"Reporte\",\"ECONOMICO\"\r\n")
				.contains("\"Cobrado\",\"1500.50\",\"DINERO\",\"ARS\",\"M19 cobro\",\"cobrado_en\"")
				// Un conteo no tiene moneda: celda vacia, no "null".
				.contains("\"Turnos\",\"3\",\"CONTEO\",\"\",\"\",\"\"")
				.contains("\"Dia\",\"Cantidad\"\r\n\"2026-09-02\",\"3\"")
				.contains("\"Secciones omitidas\",\"Permiso que falta\"\r\n\"casos\",\"hc:read\"")
				.contains("\"sin-devengado-de-financiador\",\"economia\"")
				.doesNotContain("null");
	}

	@Test
	@DisplayName("Un periodo invalido es 400 rango-de-reporte-invalido, con la ventana maxima")
	void rango_invalido_es_400() throws Exception {
		given(reporteService.generar(any(), anyLong(), any(), any(), any()))
				.willThrow(new RangoDeReporteInvalidoException("El periodo esta invertido", 366));

		mockMvc.perform(get(RUTA + "/TURNOS")
						.param("desde", "2026-09-30").param("hasta", "2026-09-01")
						.with(miembro(8L)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type", endsWith("rango-de-reporte-invalido")))
				.andExpect(jsonPath("$.detail").value("El periodo esta invertido"))
				.andExpect(jsonPath("$.maximoDias").value(366));
	}

	@Test
	@DisplayName("Una sede de otro tenant es 404 not-found, sin repetir el id en el detalle")
	void sede_ajena_es_404() throws Exception {
		given(reporteService.catalogo(any(), anyLong()))
				.willThrow(new ConsultorioNoAccesibleException(7L));

		mockMvc.perform(get(RUTA).with(miembro(8L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type", endsWith("not-found")))
				.andExpect(jsonPath("$.detail").value("El consultorio no existe."));
	}

	private static ReporteView vista() {
		AporteDeReporte economia = new AporteDeReporte(
				"economia", "Economia",
				List.of(
						IndicadorDeReporte.dinero("cobrado", "Cobrado", new BigDecimal("1500.50"),
								"ARS", "M19 cobro", "cobrado_en"),
						IndicadorDeReporte.contando("turnos", "Turnos", 3, null, null)),
				List.of("Dia", "Cantidad"),
				List.of(FilaDeReporte.de("2026-09-02", "3")),
				List.of());
		return new ReporteView(
				ReporteCode.ECONOMICO, 7L,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
				"America/Argentina/Cordoba", Instant.parse("2026-10-01T12:00:00Z"),
				List.of(economia),
				List.of(new ReporteView.SeccionOmitida("casos", "hc:read")),
				List.of(new AdvertenciaDeReporte("economia", "sin-devengado-de-financiador",
						"Sin devengado de financiador")));
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
