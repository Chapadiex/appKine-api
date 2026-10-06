package com.akine.notification.api;

import com.akine.notification.application.OperatingActor;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Arma el {@link OperatingActor} de este modulo desde la sesion y el contexto del request.
 *
 * <p>Con prefijo de modulo en el nombre: dos {@code ApiActor} colisionan por nombre de bean y
 * Spring no arranca (02.06).
 */
@Component
class NotificationApiActor {

	private final TenantContextHolder tenantContextHolder;

	NotificationApiActor(TenantContextHolder tenantContextHolder) {
		this.tenantContextHolder = tenantContextHolder;
	}

	OperatingActor current() {
		AuthenticatedPrincipal principal = principalAutenticado();
		if (principal == null) {
			throw new AccessDeniedException("La operacion requiere una sesion autenticada");
		}
		RequestTenantContext contexto = tenantContextHolder.current().orElse(null);
		return new OperatingActor(
				principal.accountId(),
				principal.platformAdmin(),
				contexto == null ? null : contexto.organizationId());
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
