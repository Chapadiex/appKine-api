package com.akine.organization.api;

import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.AuthorizedContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los contextos de trabajo de la cuenta (RF-M01-005, ADR-0009).
 *
 * <p>Es el endpoint con el que el usuario averigua QUE contextos tiene, asi que no exige uno:
 * exigirselo seria el mismo bucle que el 403 de contexto faltante existe para evitar.
 */
@WebMvcTest(MeContextController.class)
@Import(ApiSliceSecurityConfig.class)
class MeContextControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountContextDirectory accountContextDirectory;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("Devuelve cada contexto con los nombres, que es lo que el usuario ve para elegir")
	void devuelve_los_contextos_con_nombres() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
		given(accountContextDirectory.authorizedContexts(7L)).willReturn(List.of(
				new AuthorizedContext(1L, "Centro Kinesico Belgrano", 10L, "Sede Central"),
				new AuthorizedContext(2L, "Centro Kinesico Nunez", 20L, "Sede Norte")));

		mockMvc.perform(get("/api/v1/me/contexts").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].organizationId").value(1))
				.andExpect(jsonPath("$[0].organizationName").value("Centro Kinesico Belgrano"))
				.andExpect(jsonPath("$[0].consultorioId").value(10))
				.andExpect(jsonPath("$[0].consultorioName").value("Sede Central"))
				.andExpect(jsonPath("$[1].organizationId").value(2));
	}

	@Test
	@DisplayName("Una cuenta sin contextos recibe una lista vacia, no un error")
	void sin_contextos_responde_lista_vacia() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
		given(accountContextDirectory.authorizedContexts(7L)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/me/contexts").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("Sin sesion autenticada responde 403 y jamas 401: un 401 borraria el token")
	void sin_sesion_autenticada_es_403_nunca_401() throws Exception {
		mockMvc.perform(get("/api/v1/me/contexts"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verify(accountContextDirectory, never()).authorizedContexts(org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	@DisplayName("Los contextos se piden por la cuenta del principal, no por un parametro del cliente")
	void usa_la_cuenta_del_principal() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
		given(accountContextDirectory.authorizedContexts(99L)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/me/contexts")
						.param("accountId", "1")
						.with(ApiActors.miembro(99L)))
				.andExpect(status().isOk());

		verify(accountContextDirectory).authorizedContexts(99L);
		verify(accountContextDirectory, never()).authorizedContexts(1L);
	}
}
