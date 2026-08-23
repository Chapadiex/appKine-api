package com.akine.identity.api;

import com.akine.identity.application.PasswordResetService;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.exception.PasswordPolicyViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP de la recuperacion de contrasena (RF-M02-003, ADR-0018). */
@WebMvcTest(PasswordResetController.class)
@Import(IdentityApiSliceSecurityConfig.class)
class PasswordResetControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PasswordResetService passwordResetService;

	@Test
	@DisplayName("el pedido responde IDENTICO para una cuenta que existe y una que no")
	void el_pedido_responde_identico_exista_o_no_la_cuenta() throws Exception {
		// El servicio es void a proposito: no hay forma de que esta capa sepa que paso, y por
		// lo tanto no hay forma de que la respuesta varie. Esto lo fija por escrito.
		MvcResult existe = mockMvc.perform(post("/api/v1/auth/password-reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"ana.gomez@ejemplo.test"}"""))
				.andExpect(status().isAccepted())
				.andReturn();

		MvcResult noExiste = mockMvc.perform(post("/api/v1/auth/password-reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"nadie@ejemplo.test"}"""))
				.andExpect(status().isAccepted())
				.andReturn();

		assertThat(noExiste.getResponse().getStatus()).isEqualTo(existe.getResponse().getStatus());
		assertThat(noExiste.getResponse().getContentAsString())
				.isEqualTo(existe.getResponse().getContentAsString());
		assertThat(noExiste.getResponse().getHeaderNames())
				.containsExactlyInAnyOrderElementsOf(existe.getResponse().getHeaderNames());

		verify(passwordResetService).solicitar("ana.gomez@ejemplo.test");
		verify(passwordResetService).solicitar("nadie@ejemplo.test");
	}

	@Test
	@DisplayName("el pedido acepta un email que no parece uno: un 400 seria una diferencia observable")
	void el_pedido_no_valida_el_formato_del_email() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password-reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"no-es-un-email"}"""))
				.andExpect(status().isAccepted());

		verify(passwordResetService).solicitar("no-es-un-email");
	}

	@Test
	@DisplayName("el pedido sin email es 400")
	void el_pedido_sin_email_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password-reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"   "}"""))
				.andExpect(status().isBadRequest());

		verify(passwordResetService, never()).solicitar(anyString());
	}

	@Test
	@DisplayName("confirmar responde 204 y borra la cookie de refresh de este navegador")
	void confirmar_responde_204_y_borra_la_cookie() throws Exception {
		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-del-correo","password":"kinesiologia-2026"}"""))
				.andExpect(status().isNoContent())
				.andReturn();

		String cookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("akine_rt=;");
		assertThat(cookie).contains("Max-Age=0").contains("HttpOnly").contains("Secure")
				.contains("SameSite=Strict").contains("Path=/api/v1/auth");

		verify(passwordResetService).confirmar("token-del-correo", "kinesiologia-2026");
	}

	@Test
	@DisplayName("un token que no sirve responde 400 invalid-token sin decir cual de las causas fue")
	void un_token_que_no_sirve_es_400_invalid_token() throws Exception {
		willThrow(new InvalidVerificationTokenException())
				.given(passwordResetService).confirmar(anyString(), anyString());

		MvcResult usado = mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-ya-usado","password":"kinesiologia-2026"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/invalid-token"))
				.andReturn();

		MvcResult inventado = mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-inventado","password":"kinesiologia-2026"}"""))
				.andExpect(status().isBadRequest())
				.andReturn();

		assertThat(usado.getResponse().getContentAsString())
				.isEqualTo(inventado.getResponse().getContentAsString());
	}

	@Test
	@DisplayName("una contrasena nueva que no cumple la politica es 400 con el motivo explicito")
	void una_contrasena_debil_es_400_con_motivo() throws Exception {
		willThrow(new PasswordPolicyViolationException(
				"Esa contrasena aparece en listas publicas de credenciales filtradas"))
				.given(passwordResetService).confirmar(anyString(), anyString());

		mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-del-correo","password":"password12"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				.andExpect(jsonPath("$.detail")
						.value("Esa contrasena aparece en listas publicas de credenciales filtradas"));
	}

	@Test
	@DisplayName("confirmar sin contrasena nueva es 400 y no llega al servicio")
	void confirmar_sin_contrasena_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-del-correo","password":""}"""))
				.andExpect(status().isBadRequest());

		verify(passwordResetService, never()).confirmar(anyString(), any());
	}
}
