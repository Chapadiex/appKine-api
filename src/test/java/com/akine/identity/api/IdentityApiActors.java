package com.akine.identity.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Principales sinteticos para los slices de {@code identity.api}.
 *
 * <p>Mismo enfoque que {@code organization.api.ApiActors}, que es package-private de su modulo.
 * Lo que importa para estos tests es el tipo: {@code IdentityApiActor} reconoce un
 * {@link AuthenticatedPrincipal} y nada mas —el token anonimo de Spring Security se declara
 * autenticado pero su principal es un {@code String}, y ese es justamente el caso que tiene que
 * dar 403—.
 */
final class IdentityApiActors {

	private IdentityApiActors() {
	}

	/** Cuenta autenticada comun, sin privilegios de plataforma. */
	static RequestPostProcessor miembro(long accountId) {
		return principal(new PrincipalDePrueba(accountId, false));
	}

	/** Administrador de plataforma: opera por encima de cualquier tenant. */
	static RequestPostProcessor platformAdmin(long accountId) {
		return principal(new PrincipalDePrueba(accountId, true));
	}

	private static RequestPostProcessor principal(AuthenticatedPrincipal principal) {
		return authentication(
				new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
	}

	/**
	 * Principal minimo.
	 *
	 * <p>{@code organizationId} y {@code consultorioId} van nulos a proposito: son un hint del
	 * token y {@code IdentityApiActor} no los mira —toma el contexto del
	 * {@code TenantContextHolder}, que es lo unico revalidado contra la base (RN-M01-003)—.
	 */
	record PrincipalDePrueba(long accountId, boolean platformAdmin)
			implements AuthenticatedPrincipal {

		@Override
		public Long organizationId() {
			return null;
		}

		@Override
		public Long consultorioId() {
			return null;
		}
	}
}
