package com.akine.knowledge.api;

import com.akine.knowledge.application.BibliotecaAcceso;
import com.akine.knowledge.application.BusquedaService;
import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.BusquedaAjustes;
import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.AlmacenVectorial.FragmentoEncontrado;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Borde HTTP de la busqueda con el {@link BusquedaService} real y el almacen simulado. */
@WebMvcTest(BusquedaController.class)
@Import({KnowledgeApiSliceSecurityConfig.class, KnowledgeApiActor.class, BusquedaService.class,
		BusquedaControllerTest.Ajustes.class})
@DisplayName("BusquedaController - borde HTTP")
class BusquedaControllerTest {

	private static final String RUTA = "/api/v1/organizations/1/biblioteca/busquedas";

	@TestConfiguration
	static class Ajustes {
		@Bean BibliotecaAjustes bibliotecaAjustes() {
			return new BibliotecaAjustes(true, 1000);
		}

		@Bean BusquedaAjustes busquedaAjustes() {
			return new BusquedaAjustes(new BigDecimal("0.818"), new BigDecimal("0.5"), 2);
		}
	}

	@Autowired private MockMvc mockMvc;
	@MockitoBean private BibliotecaAcceso acceso;
	@MockitoBean private AlmacenVectorial almacen;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	private record PrincipalDePrueba(long accountId) implements AuthenticatedPrincipal {
		@Override public boolean platformAdmin() { return false; }
		@Override public Long organizationId() { return 1L; }
		@Override public Long consultorioId() { return 10L; }
	}

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				99L, 1L, 10L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	private static RequestPostProcessor sesion() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(99L), "n/a", List.of()));
	}

	@Test
	@DisplayName("una consulta en blanco es 400 validation-error y no busca")
	void consulta_en_blanco_es_400() throws Exception {
		mockMvc.perform(post(RUTA).with(sesion()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"consulta\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value(endsWith("validation-error")));
		verifyNoInteractions(almacen);
	}

	@Test
	@DisplayName("k fuera de 1..20 es 400")
	void k_fuera_de_rango_es_400() throws Exception {
		mockMvc.perform(post(RUTA).with(sesion()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"consulta\":\"rodilla\",\"k\":21}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("sin k pide 5; sin modelo va por palabras; el texto sale recortado a 500")
	void k_por_defecto_y_texto_recortado() throws Exception {
		given(almacen.porPalabras(eq(1L), eq("rodilla"), eq(5)))
				.willReturn(List.of(new Coincidencia(3L, new BigDecimal("0.7500"))));
		given(almacen.fragmentos(eq(1L), anyList()))
				.willReturn(List.of(new FragmentoEncontrado(3L, 7L, "Libro", 1, 1, "x".repeat(800))));

		mockMvc.perform(post(RUTA).with(sesion()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"consulta\":\"rodilla\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.camino").value("PALABRAS"))
				.andExpect(jsonPath("$.corte").value(0.5))
				.andExpect(jsonPath("$.alcanza").value(false))
				.andExpect(jsonPath("$.pasajes[0].libroId").value(7))
				.andExpect(jsonPath("$.pasajes[0].superaCorte").value(true))
				.andExpect(jsonPath("$.pasajes[0].texto").value("x".repeat(500)));
	}

	@Test
	@DisplayName("una biblioteca vacia responde 200 sin pasajes y sin alcanzar")
	void vacia_es_200() throws Exception {
		given(almacen.porPalabras(anyLong(), anyString(), anyInt())).willReturn(List.of());

		mockMvc.perform(post(RUTA).with(sesion()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"consulta\":\"rodilla\",\"k\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pasajes").isEmpty())
				.andExpect(jsonPath("$.alcanza").value(false));
	}
}
