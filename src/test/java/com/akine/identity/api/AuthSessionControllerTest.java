package com.akine.identity.api;

import com.akine.identity.application.SessionService;
import com.akine.identity.domain.exception.ContextNotAvailableException;
import com.akine.identity.domain.exception.InvalidCredentialsException;
import com.akine.identity.domain.exception.InvalidRefreshTokenException;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.AccessTokenVerifier;
import com.akine.platform.spi.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del ciclo de sesion (RF-M02-002, ADR-0017, ADR-0018).
 *
 * <p>Lo que estos tests defienden, en orden de importancia:
 * <ol>
 *   <li>que el refresh <b>no aparezca nunca en el cuerpo</b>;</li>
 *   <li>que la cookie salga con sus cuatro atributos completos, en las dos rutas que la
 *       instalan;</li>
 *   <li>que un refresh reusado sea <b>byte a byte indistinguible</b> de uno invalido;</li>
 *   <li>que un contexto no accesible responda 404 y no 403.</li>
 * </ol>
 */
@WebMvcTest(AuthSessionController.class)
@Import({IdentityApiSliceSecurityConfig.class, AuthSessionControllerTest.RelojFijo.class})
class AuthSessionControllerTest {

	private static final Instant AHORA = Instant.parse("2026-08-23T12:00:00Z");
	private static final Instant EXPIRA = AHORA.plus(12, ChronoUnit.HOURS);

	private static final String REFRESH_PLANO = "refresh-opaco-de-prueba";
	private static final String ACCESS = "eyJhbGciOiJIUzI1NiJ9.cuerpo.firma";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SessionService sessionService;

	@MockitoBean
	private AccessTokenVerifier accessTokenVerifier;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	/** El reloj no puede ser un mock: lo consume el controller para calcular el maxAge. */
	@TestConfiguration
	static class RelojFijo {

		@Bean
		IdentityClock identityClock() {
			return () -> AHORA;
		}
	}

	@BeforeEach
	void sinContextoDeTenant() {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
	}

	private static SessionService.AccesoEmitido accesoPreContexto() {
		return new SessionService.AccesoEmitido(
				ACCESS, 600L, AccessTokenScope.PRE_CONTEXT, null, null, null);
	}

	private static SessionService.SesionEmitida sesion() {
		return new SessionService.SesionEmitida(accesoPreContexto(), REFRESH_PLANO, EXPIRA);
	}

	private static String cuerpoLogin() {
		return """
				{"email":"ana.gomez@ejemplo.test","password":"kinesiologia-2026"}""";
	}

	// =================================================================================
	// Login
	// =================================================================================

	@Test
	@DisplayName("el login devuelve el access en el cuerpo y el refresh solo en la cookie")
	void el_login_devuelve_el_access_en_el_cuerpo() throws Exception {
		given(sessionService.abrirSesion(eq("ana.gomez@ejemplo.test"), eq("kinesiologia-2026"), any()))
				.willReturn(sesion());

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(cuerpoLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value(ACCESS))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(600))
				.andExpect(jsonPath("$.scope").value("pre_context"))
				.andExpect(jsonPath("$.organizationId").doesNotExist())
				.andReturn();

		assertThat(resultado.getResponse().getContentAsString())
				.as("el refresh plano no puede aparecer en el cuerpo bajo ninguna clave")
				.doesNotContain(REFRESH_PLANO);
	}

	@Test
	@DisplayName("la cookie de refresh sale con sus cuatro atributos y con el vencimiento absoluto")
	void la_cookie_sale_con_los_cuatro_atributos() throws Exception {
		given(sessionService.abrirSesion(anyString(), anyString(), any())).willReturn(sesion());

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(cuerpoLogin()))
				.andExpect(status().isOk())
				.andReturn();

		String cookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie).startsWith("akine_rt=" + REFRESH_PLANO);
		assertThat(cookie).contains("HttpOnly");
		assertThat(cookie).contains("Secure");
		assertThat(cookie).contains("SameSite=Strict");
		assertThat(cookie).contains("Path=/api/v1/auth");
		assertThat(cookie)
				.as("12 h desde el reloj fijo, o sea el vencimiento absoluto y no un TTL suelto")
				.contains("Max-Age=43200");
	}

	@Test
	@DisplayName("credenciales invalidas responden 401 invalid-credentials sin decir por que")
	void credenciales_invalidas_responden_401() throws Exception {
		willThrow(new InvalidCredentialsException())
				.given(sessionService).abrirSesion(anyString(), anyString(), any());

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(cuerpoLogin()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/invalid-credentials"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andReturn();

		assertThat(resultado.getResponse().getContentAsString().toLowerCase())
				.as("el cuerpo no puede sugerir cual de las tres causas fue")
				.doesNotContain("bloquead")
				.doesNotContain("existe")
				.doesNotContain("activ");
	}

	@Test
	@DisplayName("un login sin contrasena es 400 y no llega al servicio")
	void un_login_sin_contrasena_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"ana.gomez@ejemplo.test","password":"  "}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));

		verify(sessionService, never()).abrirSesion(anyString(), anyString(), any());
	}

	// =================================================================================
	// Refresh
	// =================================================================================

	@Test
	@DisplayName("el refresh toma la credencial de la cookie, no del cuerpo")
	void el_refresh_toma_la_credencial_de_la_cookie() throws Exception {
		given(sessionService.refrescar(eq(REFRESH_PLANO), any())).willReturn(sesion());

		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("akine_rt", REFRESH_PLANO)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value(ACCESS));

		verify(sessionService).refrescar(eq(REFRESH_PLANO), any());
	}

	@Test
	@DisplayName("el refresh renovado reinstala la cookie con los cuatro atributos")
	void el_refresh_reinstala_la_cookie_completa() throws Exception {
		given(sessionService.refrescar(anyString(), any())).willReturn(
				new SessionService.SesionEmitida(accesoPreContexto(), "refresh-sucesor", EXPIRA));

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("akine_rt", REFRESH_PLANO)))
				.andExpect(status().isOk())
				.andReturn();

		String cookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("akine_rt=refresh-sucesor");
		assertThat(cookie).contains("HttpOnly").contains("Secure")
				.contains("SameSite=Strict").contains("Path=/api/v1/auth");
		assertThat(resultado.getResponse().getContentAsString())
				.doesNotContain("refresh-sucesor");
	}

	@Test
	@DisplayName("un refresh REUSADO responde exactamente lo mismo que uno invalido")
	void un_refresh_reusado_es_indistinguible_de_uno_invalido() throws Exception {
		// El servicio no distingue los dos casos hacia afuera: reuso e invalido salen por la
		// misma excepcion. Este test fija que la capa HTTP tampoco los distinga.
		willThrow(new InvalidRefreshTokenException())
				.given(sessionService).refrescar(anyString(), any());

		MvcResult reusado = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("akine_rt", "refresh-ya-canjeado")))
				.andExpect(status().isUnauthorized())
				.andReturn();

		MvcResult inventado = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("akine_rt", "refresh-que-nunca-existio")))
				.andExpect(status().isUnauthorized())
				.andReturn();

		assertThat(reusado.getResponse().getContentAsString())
				.isEqualTo(inventado.getResponse().getContentAsString());
		assertThat(reusado.getResponse().getHeader(HttpHeaders.SET_COOKIE))
				.isEqualTo(inventado.getResponse().getHeader(HttpHeaders.SET_COOKIE));
	}

	@Test
	@DisplayName("el 401 del refresh borra la cookie para no dejar al cliente en un bucle")
	void el_401_del_refresh_borra_la_cookie() throws Exception {
		willThrow(new InvalidRefreshTokenException())
				.given(sessionService).refrescar(anyString(), any());

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("akine_rt", REFRESH_PLANO)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/invalid-refresh"))
				.andReturn();

		String cookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("akine_rt=;");
		assertThat(cookie).contains("Max-Age=0").contains("Path=/api/v1/auth");
	}

	@Test
	@DisplayName("un refresh sin cookie sale por el mismo 401 que uno invalido")
	void un_refresh_sin_cookie_es_el_mismo_401() throws Exception {
		willThrow(new InvalidRefreshTokenException())
				.given(sessionService).refrescar(any(), any());

		mockMvc.perform(post("/api/v1/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/invalid-refresh"));

		verify(sessionService).refrescar(eq(null), any());
	}

	// =================================================================================
	// Contexto
	// =================================================================================

	@Test
	@DisplayName("la seleccion de contexto usa el claim fam del bearer y devuelve un token con alcance")
	void la_seleccion_de_contexto_usa_el_claim_fam() throws Exception {
		given(accessTokenVerifier.verify("token-vivo")).willReturn(Optional.of(claims("familia-7")));
		given(sessionService.cambiarContexto(7L, "familia-7", 1L, 2L)).willReturn(
				new SessionService.AccesoEmitido(
						ACCESS, 600L, AccessTokenScope.CONTEXT, 1L, 2L, "ORG_ADMIN"));

		mockMvc.perform(post("/api/v1/auth/context")
						.header(HttpHeaders.AUTHORIZATION, "Bearer token-vivo")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"organizationId":1,"consultorioId":2}""")
						.with(IdentityApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.scope").value("context"))
				.andExpect(jsonPath("$.organizationId").value(1))
				.andExpect(jsonPath("$.consultorioId").value(2))
				.andExpect(jsonPath("$.roleCode").value("ORG_ADMIN"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

		verify(sessionService).cambiarContexto(7L, "familia-7", 1L, 2L);
	}

	@Test
	@DisplayName("sin bearer legible el contexto se emite igual, con familia nula")
	void sin_bearer_legible_la_familia_va_nula() throws Exception {
		given(sessionService.cambiarContexto(anyLong(), eq(null), anyLong(), anyLong()))
				.willReturn(new SessionService.AccesoEmitido(
						ACCESS, 600L, AccessTokenScope.CONTEXT, 1L, 2L, "ORG_ADMIN"));

		mockMvc.perform(post("/api/v1/auth/context")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"organizationId":1,"consultorioId":2}""")
						.with(IdentityApiActors.miembro(7L)))
				.andExpect(status().isOk());

		verify(sessionService).cambiarContexto(7L, null, 1L, 2L);
	}

	@Test
	@DisplayName("un contexto no accesible responde 404 y jamas 403")
	void un_contexto_no_accesible_es_404() throws Exception {
		willThrow(new ContextNotAvailableException(1L, 2L))
				.given(sessionService).cambiarContexto(anyLong(), any(), anyLong(), anyLong());

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/context")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"organizationId":1,"consultorioId":2}""")
						.with(IdentityApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"))
				.andReturn();

		assertThat(resultado.getResponse().getContentAsString())
				.as("los ids pedidos van al log, nunca a la respuesta")
				.doesNotContain("consultorio")
				.doesNotContain("organization");
	}

	@Test
	@DisplayName("sin sesion autenticada el cambio de contexto es 403, nunca 401")
	void sin_sesion_el_cambio_de_contexto_es_403() throws Exception {
		mockMvc.perform(post("/api/v1/auth/context")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"organizationId":1,"consultorioId":2}"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verify(sessionService, never()).cambiarContexto(anyLong(), any(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("un contexto con identificadores no positivos es 400 y no llega al servicio")
	void identificadores_no_positivos_son_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/context")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"organizationId":0,"consultorioId":-3}""")
						.with(IdentityApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verify(sessionService, never()).cambiarContexto(anyLong(), any(), anyLong(), anyLong());
	}

	// =================================================================================
	// Cierre
	// =================================================================================

	@Test
	@DisplayName("el logout revoca la familia, borra la cookie y responde 204")
	void el_logout_borra_la_cookie() throws Exception {
		given(sessionService.cerrarSesion(REFRESH_PLANO)).willReturn(2);

		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/logout")
						.cookie(new Cookie("akine_rt", REFRESH_PLANO)))
				.andExpect(status().isNoContent())
				.andReturn();

		String cookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("akine_rt=;");
		assertThat(cookie).contains("Max-Age=0").contains("HttpOnly").contains("Secure")
				.contains("SameSite=Strict").contains("Path=/api/v1/auth");
		verify(sessionService).cerrarSesion(REFRESH_PLANO);
	}

	@Test
	@DisplayName("un logout sin cookie tambien responde 204: no es un oraculo de tokens validos")
	void un_logout_sin_cookie_tambien_es_204() throws Exception {
		given(sessionService.cerrarSesion(null)).willReturn(0);

		mockMvc.perform(post("/api/v1/auth/logout"))
				.andExpect(status().isNoContent());

		verify(sessionService).cerrarSesion(null);
	}

	@Test
	@DisplayName("cerrar todas las sesiones opera sobre la cuenta del principal y devuelve el conteo")
	void cerrar_todas_opera_sobre_la_cuenta_del_principal() throws Exception {
		given(sessionService.cerrarTodasLasSesiones(7L)).willReturn(3);

		MvcResult resultado = mockMvc.perform(delete("/api/v1/auth/sessions")
						.param("accountId", "999")
						.with(IdentityApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closedSessions").value(3))
				.andReturn();

		verify(sessionService).cerrarTodasLasSesiones(7L);
		verify(sessionService, never()).cerrarTodasLasSesiones(999L);
		assertThat(resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE))
				.contains("Max-Age=0");
	}

	@Test
	@DisplayName("cerrar todas las sesiones sin sesion autenticada es 403, nunca 401")
	void cerrar_todas_sin_sesion_es_403() throws Exception {
		mockMvc.perform(delete("/api/v1/auth/sessions"))
				.andExpect(status().isForbidden());

		verify(sessionService, never()).cerrarTodasLasSesiones(anyLong());
	}

	private static AccessTokenClaims claims(String familia) {
		return new AccessTokenClaims(
				7L, "jti-1", AccessTokenScope.PRE_CONTEXT, null, null, null, familia,
				AHORA, AHORA.plus(10, ChronoUnit.MINUTES));
	}
}
