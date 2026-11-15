package com.akine.assistant.api;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Arma el {@link ActorDelAsistente} desde la sesion y el contexto del request.
 *
 * <p>Con prefijo de modulo en el nombre: dos {@code ApiActor} colisionan por nombre de bean y Spring
 * no arranca (02.06). La sede del actor la fija el servicio con la de la ruta.
 */
@Component
class AsistenteApiActor {

	private final TenantContextHolder tenantContextHolder;

	AsistenteApiActor(TenantContextHolder tenantContextHolder) {
		this.tenantContextHolder = tenantContextHolder;
	}

	ActorDelAsistente current() {
		AuthenticatedPrincipal principal = principalAutenticado();
		if (principal == null) {
			throw new AccessDeniedException("La operacion requiere una sesion autenticada");
		}
		RequestTenantContext contexto = tenantContextHolder.current().orElse(null);
		return new ActorDelAsistente(
				principal.accountId(),
				principal.platformAdmin(),
				contexto == null ? null : contexto.organizationId(),
				contexto == null ? null : contexto.consultorioId(),
				contexto == null ? null : contexto.roleCode());
	}

	private static AuthenticatedPrincipal principalAutenticado() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		Object principal = authentication.getPrincipal();
		return principal instanceof AuthenticatedPrincipal akine ? akine : null;
	}
}
