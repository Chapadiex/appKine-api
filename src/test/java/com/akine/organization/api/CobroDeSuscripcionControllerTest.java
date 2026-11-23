package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.CobroDeSuscripcionService;
import com.akine.organization.application.CobroDeSuscripcionService.CobroView;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.CobroNoAdmitidoException;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP del cobro mensual de la suscripcion (19/11/2026). */
@WebMvcTest(CobroDeSuscripcionController.class)
@Import(ApiSliceSecurityConfig.class)
class CobroDeSuscripcionControllerTest {

	private static final String RUTA = "/api/v1/platform/organizations/7/cobros";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CobroDeSuscripcionService cobros;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("La plataforma registra un cobro: 201 con el periodo, y el historial viene del mas nuevo")
	void registra_y_lista() throws Exception {
		given(cobros.registrar(7L, 3, "Transferencia", 1L)).willReturn(cobro());
		given(cobros.historial(7L)).willReturn(List.of(cobro()));

		mockMvc.perform(post(RUTA).with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"meses\":3,\"nota\":\"Transferencia\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(12))
				.andExpect(jsonPath("$.meses").value(3))
				.andExpect(jsonPath("$.periodoHasta").value("2027-02-19T15:00:00Z"))
				.andExpect(jsonPath("$.registradoPor").value(1));

		mockMvc.perform(get(RUTA).with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(12));
	}

	@Test
	@DisplayName("Meses fuera de 1 a 12 es 400 y no llega al servicio")
	void meses_fuera_de_rango() throws Exception {
		mockMvc.perform(post(RUTA).with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"meses\":13}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(cobros);
	}

	@Test
	@DisplayName("Una suscripcion suspendida no admite cobro: 409 subscription-suspended con el estado")
	void suspendida_es_409() throws Exception {
		given(cobros.registrar(anyLong(), anyInt(), any(), any()))
				.willThrow(new CobroNoAdmitidoException(7L, SubscriptionStatus.SUSPENDIDA));

		mockMvc.perform(post(RUTA).with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"meses\":1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("subscription-suspended")))
				.andExpect(jsonPath("$.subscriptionStatus").value("SUSPENDIDA"));
	}

	@Test
	@DisplayName("Quien no es de plataforma recibe 403 y no se registra nada")
	void sin_rol_de_plataforma_es_403() throws Exception {
		willThrow(new AccessDeniedException("Operacion reservada a la administracion de plataforma"))
				.given(authorizationGuard).requirePlatformAdmin(false);

		mockMvc.perform(post(RUTA).with(ApiActors.miembro(2L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"meses\":1}"))
				.andExpect(status().isForbidden());

		verifyNoInteractions(cobros);
	}

	private static CobroView cobro() {
		return new CobroView(12L, Instant.parse("2026-11-19T15:00:00Z"), Instant.parse("2027-02-19T15:00:00Z"), 3,
				"Transferencia", 1L, Instant.parse("2026-11-19T15:00:00Z"));
	}
}
