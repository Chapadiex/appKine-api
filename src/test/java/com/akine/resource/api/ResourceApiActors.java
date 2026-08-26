package com.akine.resource.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Principales sinteticos para los slices de {@code resource.api}.
 *
 * <p>Lo que importa para estos tests es el TIPO: {@code ApiActor} reconoce un
 * {@link AuthenticatedPrincipal} y nada mas. El token anonimo de Spring Security se declara
 * autenticado pero su principal es un {@code String}, y ese es justamente el caso que tiene que
 * dar 403 y nunca 401.
 */
final class ResourceApiActors {

	private ResourceApiActors() {
	}

	/** Cuenta autenticada comun, sin privilegios de plataforma. */
	static RequestPostProcessor miembro(long accountId) {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(accountId, false), "n/a", List.of()));
	}

	/**
	 * Principal minimo.
	 *
	 * <p>{@code organizationId} y {@code consultorioId} van nulos a proposito: son un hint del
	 * token y {@code ApiActor} no los mira —toma el contexto del {@code TenantContextHolder}, que
	 * es lo unico revalidado contra la base (RN-M01-003)—.
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
