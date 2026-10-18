package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.OrganizationService;
import com.akine.organization.domain.OrganizationListing;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del listado de centros de la administracion de plataforma.
 *
 * <p>Lo propio del controller: exigir el rol de plataforma antes de leer nada, acotar el paginado
 * y publicar solo los datos minimos de cada centro.
 */
@WebMvcTest(PlatformOrganizationController.class)
@Import(ApiSliceSecurityConfig.class)
class PlatformOrganizationControllerTest {

	private static final String RUTA = "/api/v1/platform/organizations";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrganizationService organizationService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("El admin de plataforma recibe la pagina con los datos minimos de cada centro")
	void plataforma_recibe_la_pagina() throws Exception {
		given(organizationService.listForPlatform(
				eq("norte"), eq(SubscriptionStatus.SUSPENDIDA), any()))
				.willReturn(new PageImpl<>(List.of(centro()), PageRequest.of(0, 20), 1));

		mockMvc.perform(get(RUTA).param("q", "norte").param("status", "SUSPENDIDA")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(7))
				.andExpect(jsonPath("$.content[0].name").value("Centro Norte"))
				.andExpect(jsonPath("$.content[0].slug").value("centro-norte"))
				.andExpect(jsonPath("$.content[0].status").value("SUSPENDIDA"))
				.andExpect(jsonPath("$.content[0].planCode").value("BASICO"))
				.andExpect(jsonPath("$.content[0].planName").value("Basico"))
				.andExpect(jsonPath("$.content[0].createdAt").exists())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20));

		verify(authorizationGuard).requirePlatformAdmin(true);
	}

	@Test
	@DisplayName("Un tamano de pagina desmedido se acota a 100 y una pagina negativa pasa a 0")
	void paginado_acotado() throws Exception {
		given(organizationService.listForPlatform(any(), any(), any()))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		mockMvc.perform(get(RUTA).param("page", "-3").param("size", "5000")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk());

		verify(organizationService).listForPlatform(null, null, PageRequest.of(0, 100));
	}

	@Test
	@DisplayName("Quien no es de plataforma recibe 403 y el listado no se lee")
	void sin_rol_de_plataforma_es_403() throws Exception {
		willThrow(new AccessDeniedException("Operacion reservada a la administracion de plataforma"))
				.given(authorizationGuard).requirePlatformAdmin(false);

		mockMvc.perform(get(RUTA).with(ApiActors.miembro(2L)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(organizationService);
	}

	@Test
	@DisplayName("Un estado que no es de suscripcion es 400 y no llega al servicio")
	void estado_invalido_es_400() throws Exception {
		mockMvc.perform(get(RUTA).param("status", "BORRADA").with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(organizationService);
	}

	private static OrganizationListing centro() {
		return new OrganizationListing(
				7L, "Centro Norte", "centro-norte", SubscriptionStatus.SUSPENDIDA,
				"BASICO", "Basico", Instant.parse("2026-01-15T13:45:00Z"));
	}
}
