package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.ContratacionService;
import com.akine.organization.application.ContratacionView;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP de la contratacion de un centro (18/11/2026). */
@WebMvcTest(ContratacionController.class)
@Import(ApiSliceSecurityConfig.class)
class ContratacionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ContratacionService contratacionService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("El centro lee su contratacion: servicios y adicionales ordenados, prueba y version")
	void el_centro_lee_su_contratacion() throws Exception {
		given(contratacionService.ver(7L)).willReturn(vista(EnumSet.allOf(Servicio.class)));

		mockMvc.perform(get("/api/v1/organizations/7/contratacion").with(ApiActors.miembro(3L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.planCode").value("CRECIMIENTO"))
				.andExpect(jsonPath("$.maxSedes").value(3))
				.andExpect(jsonPath("$.servicios[0]").value("KINESIOLOGIA"))
				.andExpect(jsonPath("$.servicios[1]").value("ACTIVIDADES"))
				.andExpect(jsonPath("$.adicionales[0]").value("IA"))
				.andExpect(jsonPath("$.estado").value("PRUEBA"))
				.andExpect(jsonPath("$.pruebaHasta").exists())
				.andExpect(jsonPath("$.soloLectura").value(false))
				.andExpect(jsonPath("$.definidaPorPlataforma").value(true))
				.andExpect(jsonPath("$.version").value(4));

		verify(authorizationGuard).requireMember(eq(3L), eq(7L), isNull(), eq(false));
	}

	@Test
	@DisplayName("La plataforma lee la contratacion de cualquier centro")
	void la_plataforma_la_lee() throws Exception {
		given(contratacionService.ver(7L)).willReturn(vista(EnumSet.of(Servicio.ACTIVIDADES)));

		mockMvc.perform(get("/api/v1/platform/organizations/7/contratacion").with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.servicios.length()").value(1))
				.andExpect(jsonPath("$.servicios[0]").value("ACTIVIDADES"));

		verify(authorizationGuard).requirePlatformAdmin(true);
	}

	@Test
	@DisplayName("Sin rol de plataforma no se lee nada")
	void sin_rol_de_plataforma_403() throws Exception {
		willThrow(new AccessDeniedException("no")).given(authorizationGuard).requirePlatformAdmin(anyBoolean());

		mockMvc.perform(get("/api/v1/platform/organizations/7/contratacion").with(ApiActors.miembro(3L)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(contratacionService);
	}

	@Test
	@DisplayName("Cambiar la contratacion pasa plan, servicios, adicionales, contratar y version al servicio")
	void cambiar() throws Exception {
		given(contratacionService.cambiar(7L, "BASE", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), true, 4L, 1L))
				.willReturn(vista(EnumSet.of(Servicio.KINESIOLOGIA)));

		mockMvc.perform(put("/api/v1/platform/organizations/7/contratacion").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planCode":"BASE","servicios":["KINESIOLOGIA"],"adicionales":[],
								 "contratar":true,"version":4}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.servicios[0]").value("KINESIOLOGIA"));
	}

	@Test
	@DisplayName("Sin servicios la contratacion se rechaza con 400 antes de tocar el servicio")
	void sin_servicios_400() throws Exception {
		mockMvc.perform(put("/api/v1/platform/organizations/7/contratacion").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planCode":"BASE","servicios":[],"adicionales":[],"version":4}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(contratacionService);
	}

	private static ContratacionView vista(Set<Servicio> servicios) {
		return new ContratacionView(7L, "CRECIMIENTO", "Plan Intermedio", 3, servicios, EnumSet.of(Adicional.IA),
				SubscriptionStatus.PRUEBA, Instant.parse("2026-12-18T03:00:00Z"), false, true, true, 4L);
	}
}
