package com.akine.organization.api;

import com.akine.organization.application.PlatformRoleService;
import com.akine.organization.application.PlatformRoleView;
import com.akine.organization.domain.exception.PlatformRoleNotFoundException;
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
 * Contrato HTTP de los roles de plataforma.
 *
 * <p>La autorizacion la decide el servicio contra la base, no el flag del token: por eso el
 * rechazo se simula desde el servicio. Lo propio del controller es el puente
 * {@code IllegalStateException} → 409, sin el cual el otorgamiento duplicado caeria en la red
 * de contencion del advice global como 500.
 */
@WebMvcTest(PlatformRoleController.class)
@Import(ApiSliceSecurityConfig.class)
class PlatformRoleControllerTest {

	private static final String BASE = "/api/v1/platform/roles";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PlatformRoleService platformRoleService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("El listado devuelve los roles vigentes y consulta con la cuenta del actor")
	void listado_devuelve_los_vigentes() throws Exception {
		given(platformRoleService.list(1L)).willReturn(List.of(rol()));

		mockMvc.perform(get(BASE).with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(2))
				.andExpect(jsonPath("$[0].accountId").value(5))
				.andExpect(jsonPath("$[0].roleCode").value("PLATFORM_ADMIN"))
				.andExpect(jsonPath("$[0].grantedByAccountId").value(1))
				.andExpect(jsonPath("$[0].reason").value("Soporte de guardia"))
				.andExpect(jsonPath("$[0].active").value(true));
	}

	@Test
	@DisplayName("Sin sesion autenticada es 403, jamas 401, y no llega al servicio")
	void listado_sin_sesion_es_403() throws Exception {
		mockMvc.perform(get(BASE))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verifyNoInteractions(platformRoleService);
	}

	@Test
	@DisplayName("Una cuenta que no administra la plataforma recibe 403")
	void listado_sin_rol_de_plataforma_es_403() throws Exception {
		given(platformRoleService.list(7L)).willThrow(
				new AccessDeniedException("Operacion reservada a la administracion de plataforma"));

		mockMvc.perform(get(BASE).with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Otorgar responde 201 y entrega actor, cuenta destino y motivo")
	void otorgar_es_201() throws Exception {
		given(platformRoleService.grant(1L, 5L, "Soporte de guardia")).willReturn(rol());

		mockMvc.perform(post(BASE)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountId\":5,\"reason\":\"Soporte de guardia\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(2))
				.andExpect(jsonPath("$.accountId").value(5));

		verify(platformRoleService).grant(1L, 5L, "Soporte de guardia");
	}

	@Test
	@DisplayName("Sin cuenta destino ni motivo es 400 con los dos campos")
	void otorgar_sin_campos_es_400() throws Exception {
		mockMvc.perform(post(BASE)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.accountId").exists())
				.andExpect(jsonPath("$.errors.reason").exists());

		verifyNoInteractions(platformRoleService);
	}

	@Test
	@DisplayName("Una cuenta destino no positiva es 400 antes de tocar la base")
	void otorgar_con_cuenta_invalida_es_400() throws Exception {
		mockMvc.perform(post(BASE)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountId\":0,\"reason\":\"x\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.accountId").exists());
	}

	@Test
	@DisplayName("Otorgar a quien ya lo tiene es 409 conflict, no el 500 de la red de contencion")
	void otorgar_duplicado_es_409() throws Exception {
		given(platformRoleService.grant(anyLong(), anyLong(), any()))
				.willThrow(new IllegalStateException("Esa cuenta ya tiene el rol de plataforma vigente"));

		mockMvc.perform(post(BASE)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountId\":5,\"reason\":\"Soporte de guardia\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/conflict"))
				.andExpect(jsonPath("$.title").value("Rol de plataforma ya otorgado"));
	}

	@Test
	@DisplayName("Revocar es 204 aun con Accept problem+json, y el motivo viaja por query")
	void revocar_es_204_con_accept_problem_json() throws Exception {
		mockMvc.perform(delete(BASE + "/2")
						.param("reason", "Cuenta comprometida")
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNoContent());

		verify(platformRoleService).revoke(1L, 2L, "Cuenta comprometida");
	}

	@Test
	@DisplayName("Revocar un rol inexistente o ya revocado es 404")
	void revocar_inexistente_es_404() throws Exception {
		willThrow(new PlatformRoleNotFoundException(99L))
				.given(platformRoleService).revoke(1L, 99L, null);

		mockMvc.perform(delete(BASE + "/99").with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	private static PlatformRoleView rol() {
		return new PlatformRoleView(
				2L, 5L, "PLATFORM_ADMIN", 1L, "Soporte de guardia",
				Instant.parse("2026-02-01T10:00:00Z"), null, true);
	}
}
