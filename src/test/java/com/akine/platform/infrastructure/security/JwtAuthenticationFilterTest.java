package com.akine.platform.infrastructure.security;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.AccessTokenVerifier;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;

import jakarta.servlet.FilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El filtro que traduce un bearer en un principal.
 *
 * <p>Dos comportamientos se prueban aparte del camino feliz, y los dos son de seguridad:
 * {@link #el_contexto_se_limpia_al_terminar()} —los hilos del contenedor se reutilizan y un
 * principal que sobrevive al request se lo lleva puesto el proximo usuario— y
 * {@link #un_token_pre_contexto_no_publica_organizacion()} —un token sin contexto no puede
 * aparentar tenerlo, porque {@code TenantContextFilter} decide por esos dos campos—.
 */
class JwtAuthenticationFilterTest {

	private static final String RUTA = "/api/v1/probe";

	@AfterEach
	void limpiar() {
		SecurityContextHolder.clearContext();
	}

	private static AccessTokenClaims claims(AccessTokenScope alcance, String rol) {
		return new AccessTokenClaims(
				42L, "jti", alcance,
				alcance == AccessTokenScope.CONTEXT ? 10L : null,
				alcance == AccessTokenScope.CONTEXT ? 20L : null,
				rol, "familia-1", Instant.now(), Instant.now().plusSeconds(600));
	}

	private static JwtAuthenticationFilter filtroQueAcepta(AccessTokenClaims claims) {
		AccessTokenVerifier verificador = token -> Optional.of(claims);
		return new JwtAuthenticationFilter(verificador);
	}

	private static JwtAuthenticationFilter filtroQueRechaza() {
		AccessTokenVerifier verificador = token -> Optional.empty();
		return new JwtAuthenticationFilter(verificador);
	}

	private static MockHttpServletRequest conBearer(String valor) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", RUTA);
		if (valor != null) {
			request.addHeader("Authorization", valor);
		}
		return request;
	}

	@Test
	@DisplayName("publica el principal y lo limpia al terminar el request")
	void el_contexto_se_limpia_al_terminar() throws Exception {
		AuthenticatedPrincipal[] visto = new AuthenticatedPrincipal[1];
		FilterChain cadena = (req, res) ->
				visto[0] = (AuthenticatedPrincipal) SecurityContextHolder.getContext()
						.getAuthentication().getPrincipal();

		filtroQueAcepta(claims(AccessTokenScope.CONTEXT, "ORG_ADMIN"))
				.doFilter(conBearer("Bearer abc"), new MockHttpServletResponse(), cadena);

		assertThat(visto[0]).isNotNull();
		assertThat(visto[0].accountId()).isEqualTo(42L);
		assertThat(visto[0].organizationId()).isEqualTo(10L);
		assertThat(visto[0].consultorioId()).isEqualTo(20L);
		// Y despues del request no queda nada en el hilo.
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("un token pre-contexto no publica organizacion ni consultorio")
	void un_token_pre_contexto_no_publica_organizacion() throws Exception {
		AuthenticatedPrincipal[] visto = new AuthenticatedPrincipal[1];
		FilterChain cadena = (req, res) ->
				visto[0] = (AuthenticatedPrincipal) SecurityContextHolder.getContext()
						.getAuthentication().getPrincipal();

		filtroQueAcepta(claims(AccessTokenScope.PRE_CONTEXT, null))
				.doFilter(conBearer("Bearer abc"), new MockHttpServletResponse(), cadena);

		assertThat(visto[0].organizationId()).isNull();
		assertThat(visto[0].consultorioId()).isNull();
		assertThat(visto[0].platformAdmin()).isFalse();
	}

	@Test
	@DisplayName("el rol PLATFORM_ADMIN del claim marca al principal, y ningun otro lo hace")
	void solo_platform_admin_marca_al_principal() {
		assertThat(new AuthenticatedJwtPrincipal(
				claims(AccessTokenScope.CONTEXT, "PLATFORM_ADMIN")).platformAdmin()).isTrue();
		assertThat(new AuthenticatedJwtPrincipal(
				claims(AccessTokenScope.CONTEXT, "ORG_ADMIN")).platformAdmin()).isFalse();
		assertThat(new AuthenticatedJwtPrincipal(
				claims(AccessTokenScope.CONTEXT, null)).platformAdmin()).isFalse();
	}

	@Test
	@DisplayName("sin cabecera el request sigue anonimo: la ruta decide si eso alcanza")
	void sin_cabecera_sigue_anonimo() throws Exception {
		MockFilterChain cadena = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filtroQueRechaza().doFilter(conBearer(null), response, cadena);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(cadena.getRequest()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("una cabecera vacia o sin valor tras el esquema se trata como ausencia")
	void una_cabecera_sin_valor_es_ausencia() throws Exception {
		for (String header : new String[]{"", "Bearer", "Bearer ", "Bearer    ", "Token abc"}) {
			MockFilterChain cadena = new MockFilterChain();
			MockHttpServletResponse response = new MockHttpServletResponse();

			filtroQueRechaza().doFilter(conBearer(header), response, cadena);

			assertThat(response.getStatus()).as("header=%s", header).isEqualTo(200);
			assertThat(cadena.getRequest()).as("header=%s", header).isNotNull();
		}
	}

	@Test
	@DisplayName("un token presente e invalido corta con 401 y no llega a la cadena")
	void un_token_invalido_corta_con_401() throws Exception {
		MockFilterChain cadena = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filtroQueRechaza().doFilter(conBearer("Bearer basura"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getContentAsString())
				.contains("https://akine.app/problems/unauthorized")
				// El cuerpo no repite el token presentado: seria una credencial en la respuesta.
				.doesNotContain("basura");
		assertThat(cadena.getRequest()).isNull();
	}

	@Test
	@DisplayName("no se escribe sobre una respuesta ya comprometida")
	void no_se_escribe_sobre_una_respuesta_comprometida() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(200);
		response.getWriter().write("ya escrito");
		response.flushBuffer();

		filtroQueRechaza().doFilter(conBearer("Bearer basura"), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).isEqualTo("ya escrito");
	}
}
