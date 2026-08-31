package com.akine.scheduling.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.scheduling.application.OperatingActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Arma el {@link OperatingActor} de este modulo desde la sesion y el contexto del request.
 *
 * <p><b>El nombre lleva el prefijo del modulo a proposito.</b> Dos clases {@code ApiActor} en
 * paquetes distintos colisionan por nombre de bean y Spring no arranca — es la leccion que dejo
 * 02.06 y esta escrita en el estado del proyecto. {@code resource} tiene el suyo llamado
 * {@code ApiActor} porque fue el primero; todos los posteriores llevan prefijo.
 */
@Component
class SchedulingApiActor {

	private final TenantContextHolder tenantContextHolder;

	SchedulingApiActor(TenantContextHolder tenantContextHolder) {
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
				contexto == null ? null : contexto.organizationId(),
				contexto == null ? null : contexto.consultorioId());
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
