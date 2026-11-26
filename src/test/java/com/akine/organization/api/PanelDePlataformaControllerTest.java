package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.PanelDePlataformaService;
import com.akine.organization.domain.PanelDePlataforma;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP del panel de la consola de plataforma (19/11/2026). */
@WebMvcTest(PanelDePlataformaController.class)
@Import(ApiSliceSecurityConfig.class)
class PanelDePlataformaControllerTest {

	private static final Instant CUANDO = Instant.parse("2026-11-19T12:00:00Z");

	@Autowired private MockMvc mockMvc;
	@MockitoBean private PanelDePlataformaService service;
	@MockitoBean private AuthorizationGuard authorizationGuard;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("La plataforma lee los conteos y los ultimos movimientos")
	void la_plataforma_lee_el_panel() throws Exception {
		given(service.leer()).willReturn(new PanelDePlataforma(
				new PanelDePlataforma.Centros(12, 8, 3, 1, 1, 0, 0), 2,
				List.of(new PanelDePlataforma.PruebaQueTermina(7, "Centro Norte", CUANDO)),
				List.of(new PanelDePlataforma.CentroReciente(7, "Centro Norte", "PRUEBA", CUANDO)),
				List.of(new PanelDePlataforma.PedidoReciente(5, "Estudio Pilates", "Ana Gomez", "PENDIENTE", CUANDO)),
				List.of(new PanelDePlataforma.CambioDeSuscripcion(7, "Centro Norte", "PRUEBA", "ACTIVA", CUANDO)), 1));

		mockMvc.perform(get("/api/v1/platform/resumen").with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.centros.total").value(12))
				.andExpect(jsonPath("$.centros.pruebaPorVencer").value(1))
				.andExpect(jsonPath("$.pedidosPendientes").value(2))
				.andExpect(jsonPath("$.pruebasQueTerminan[0].nombre").value("Centro Norte"))
				.andExpect(jsonPath("$.ultimosCentros[0].estado").value("PRUEBA"))
				.andExpect(jsonPath("$.ultimosPedidos[0].persona").value("Ana Gomez"))
				.andExpect(jsonPath("$.ultimosCambios[0].hacia").value("ACTIVA"));
	}

	@Test
	@DisplayName("Sin rol de plataforma no se lee nada")
	void sin_rol_403() throws Exception {
		willThrow(new AccessDeniedException("no")).given(authorizationGuard).requirePlatformAdmin(anyBoolean());

		mockMvc.perform(get("/api/v1/platform/resumen").with(ApiActors.miembro(3L)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}
}
