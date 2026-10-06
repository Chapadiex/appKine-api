package com.akine.organization.api;

import com.akine.organization.application.SupportAccessService;
import com.akine.organization.application.SupportAccessView;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.SupportAccessNotFoundException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del acceso de soporte de plataforma a un tenant.
 *
 * <p>Lo propio del controller es convertir minutos en {@code Duration} —y dejar {@code null}
 * cuando no vienen, para que el servicio aplique el default— y el tope de 240 minutos, que es
 * validacion del request y no un valor sugerido.
 */
@WebMvcTest(PlatformSupportAccessController.class)
@Import(ApiSliceSecurityConfig.class)
class PlatformSupportAccessControllerTest {

	private static final String ACCESOS_DE_LA_ORG = "/api/v1/platform/organizations/7/support-access";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SupportAccessService supportAccessService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("El listado devuelve los accesos vigentes de la organizacion")
	void listado_devuelve_los_vigentes() throws Exception {
		given(supportAccessService.list(1L, 7L)).willReturn(List.of(acceso()));

		mockMvc.perform(get(ACCESOS_DE_LA_ORG).with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(5))
				.andExpect(jsonPath("$[0].organizationId").value(7))
				.andExpect(jsonPath("$[0].accountId").value(1))
				.andExpect(jsonPath("$[0].reason").value("Ticket 123"))
				.andExpect(jsonPath("$[0].validUntil").exists())
				.andExpect(jsonPath("$[0].revokedAt").doesNotExist())
				.andExpect(jsonPath("$[0].active").value(true));
	}

	@Test
	@DisplayName("Sin sesion autenticada es 403, jamas 401, y no llega al servicio")
	void listado_sin_sesion_es_403() throws Exception {
		mockMvc.perform(get(ACCESOS_DE_LA_ORG))
				.andExpect(status().isForbidden());

		verifyNoInteractions(supportAccessService);
	}

	@Test
	@DisplayName("Conceder responde 201 y convierte los minutos pedidos en una duracion")
	void conceder_convierte_minutos_en_duracion() throws Exception {
		given(supportAccessService.grant(1L, 7L, "Ticket 123", Duration.ofMinutes(30)))
				.willReturn(acceso());

		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ticket 123\",\"durationMinutes\":30}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(5));

		verify(supportAccessService).grant(1L, 7L, "Ticket 123", Duration.ofMinutes(30));
	}

	@Test
	@DisplayName("Sin duracion se entrega null: el default lo decide el servicio, no el controller")
	void conceder_sin_duracion_entrega_null() throws Exception {
		given(supportAccessService.grant(1L, 7L, "Ticket 123", null)).willReturn(acceso());

		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ticket 123\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isCreated());

		verify(supportAccessService).grant(1L, 7L, "Ticket 123", null);
	}

	@Test
	@DisplayName("Mas de cuatro horas, cero minutos o sin motivo: 400 sin tocar el servicio")
	void conceder_fuera_de_rango_o_sin_motivo_es_400() throws Exception {
		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ticket 123\",\"durationMinutes\":241}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.durationMinutes").exists());

		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ticket 123\",\"durationMinutes\":0}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.durationMinutes").exists());

		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"durationMinutes\":60}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.reason").exists());

		verifyNoInteractions(supportAccessService);
	}

	@Test
	@DisplayName("Una cuenta que no administra la plataforma recibe 403 al pedir acceso")
	void conceder_sin_rol_de_plataforma_es_403() throws Exception {
		given(supportAccessService.grant(anyLong(), anyLong(), any(), any())).willThrow(
				new AccessDeniedException(
						"El acceso de soporte es una operacion de administracion de plataforma"));

		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ticket 123\"}")
						.with(ApiActors.miembro(9L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));
	}

	@Test
	@DisplayName("Una organizacion inexistente o dada de baja es 404")
	void conceder_sobre_organizacion_inexistente_es_404() throws Exception {
		given(supportAccessService.grant(anyLong(), anyLong(), any(), any()))
				.willThrow(new OrganizationNotFoundException(7L));

		mockMvc.perform(post(ACCESOS_DE_LA_ORG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ticket 123\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	@Test
	@DisplayName("Cerrar un acceso es 204 aun con Accept problem+json")
	void cerrar_es_204_con_accept_problem_json() throws Exception {
		mockMvc.perform(delete("/api/v1/platform/support-access/5")
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNoContent());

		verify(supportAccessService).revoke(1L, 5L);
	}

	@Test
	@DisplayName("Cerrar un acceso inexistente o ya cerrado es 404: los dos casos son el mismo")
	void cerrar_inexistente_es_404() throws Exception {
		willThrow(new SupportAccessNotFoundException(99L))
				.given(supportAccessService).revoke(1L, 99L);

		mockMvc.perform(delete("/api/v1/platform/support-access/99")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	private static SupportAccessView acceso() {
		return new SupportAccessView(
				5L, 7L, 1L, "Ticket 123", 1L,
				Instant.parse("2026-08-20T12:00:00Z"),
				Instant.parse("2026-08-20T16:00:00Z"),
				null, true);
	}
}
