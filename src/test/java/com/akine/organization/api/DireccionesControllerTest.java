package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.DireccionesService;
import com.akine.organization.application.Geocodificador;
import com.akine.organization.application.GeocodificadorNoDisponibleException;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DireccionesController.class)
@Import(ApiSliceSecurityConfig.class)
class DireccionesControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DireccionesService direccionesService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("devuelve los candidatos con proveedor y atribucion, y exige ser miembro")
	void busca_direcciones() throws Exception {
		given(direccionesService.buscar("Av. Colon 1234", "AR")).willReturn(
				new DireccionesService.BusquedaDeDirecciones("NOMINATIM", "(c) OpenStreetMap contributors",
						List.of(new Geocodificador.DireccionEncontrada(
								"Avenida Colon 1234, Cordoba, Argentina",
								new BigDecimal("-31.4135"), new BigDecimal("-64.1923"),
								"Avenida Colon", "1234", "Cordoba", "Cordoba", "X5000", "AR"))));

		mockMvc.perform(get("/api/v1/organizations/1/direcciones/busqueda")
						.param("q", "Av. Colon 1234")
						.param("pais", "AR")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.proveedor").value("NOMINATIM"))
				.andExpect(jsonPath("$.atribucion").value("(c) OpenStreetMap contributors"))
				.andExpect(jsonPath("$.resultados[0].etiqueta").value("Avenida Colon 1234, Cordoba, Argentina"))
				.andExpect(jsonPath("$.resultados[0].latitud").value(-31.4135))
				.andExpect(jsonPath("$.resultados[0].calle").value("Avenida Colon"))
				.andExpect(jsonPath("$.resultados[0].pais").value("AR"));

		verify(authorizationGuard).requireMember(eq(7L), eq(1L), any(), eq(false));
	}

	@Test
	@DisplayName("un texto en blanco es 400 validation-error antes de tocar el proveedor")
	void texto_en_blanco_es_400() throws Exception {
		mockMvc.perform(get("/api/v1/organizations/1/direcciones/busqueda")
						.param("q", "   ")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));

		verifyNoInteractions(direccionesService);
	}

	@Test
	@DisplayName("sin proveedor es 503 geocodificador-no-disponible con Retry-After")
	void proveedor_caido_es_503() throws Exception {
		given(direccionesService.buscar(any(), any()))
				.willThrow(new GeocodificadorNoDisponibleException("Nominatim no respondio"));

		mockMvc.perform(get("/api/v1/organizations/1/direcciones/busqueda")
						.param("q", "Av. Colon 1234")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/geocodificador-no-disponible"));
	}
}
