package com.akine.organization.api;

import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Traduccion del request en curso a los primitivos que espera el guard de autorizacion.
 *
 * <p>El slice web no puede ejercitar el caso "no hay ningun {@code Authentication}": la cadena
 * de Spring Security siempre deja el token anonimo. Ese camino se prueba aca, manipulando el
 * contexto de seguridad directamente.
 */
class ApiActorTest {

	private final TenantContextHolder tenantContextHolder = mock(TenantContextHolder.class);

	@AfterEach
	void limpiarContextoDeSeguridad() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("Sin ningun Authentication en el contexto se rechaza con 403, jamas con 401")
	void sin_authentication_se_rechaza() {
		SecurityContextHolder.clearContext();

		assertThatThrownBy(() -> ApiActor.current(tenantContextHolder))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessageContaining("sesion autenticada");
	}

	@Test
	@DisplayName("El token anonimo se declara autenticado pero no es un principal de AKINE")
	void el_token_anonimo_no_alcanza() {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
				"key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThatThrownBy(() -> ApiActor.current(tenantContextHolder))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("Un Authentication marcado como no autenticado tampoco produce actor")
	void un_authentication_no_autenticado_no_alcanza() {
		UsernamePasswordAuthenticationToken sinAutenticar =
				UsernamePasswordAuthenticationToken.unauthenticated(
						new ApiActors.PrincipalDePrueba(7L, false), "n/a");
		SecurityContextHolder.getContext().setAuthentication(sinAutenticar);

		assertThatThrownBy(() -> ApiActor.current(tenantContextHolder))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("Sin contexto de tenant el actor queda con organizacion nula, que es legitimo")
	void sin_contexto_de_tenant_la_organizacion_es_nula() {
		autenticar(7L, true);
		given(tenantContextHolder.current()).willReturn(Optional.empty());

		ApiActor actor = ApiActor.current(tenantContextHolder);

		assertThat(actor.accountId()).isEqualTo(7L);
		assertThat(actor.platformAdmin()).isTrue();
		assertThat(actor.contextOrganizationId()).isNull();
	}

	@Test
	@DisplayName("La organizacion del actor sale del contexto revalidado, no de los claims del token")
	void la_organizacion_sale_del_contexto_revalidado() {
		autenticar(7L, false);
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				7L, 42L, 100L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));

		ApiActor actor = ApiActor.current(tenantContextHolder);

		assertThat(actor.accountId()).isEqualTo(7L);
		assertThat(actor.platformAdmin()).isFalse();
		assertThat(actor.contextOrganizationId()).isEqualTo(42L);
	}

	private static void autenticar(long accountId, boolean platformAdmin) {
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(
						new ApiActors.PrincipalDePrueba(accountId, platformAdmin),
						"n/a",
						AuthorityUtils.NO_AUTHORITIES));
	}
}
