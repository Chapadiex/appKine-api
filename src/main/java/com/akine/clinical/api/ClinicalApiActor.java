package com.akine.clinical.api;

import com.akine.clinical.application.OperatingActor;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Arma el {@link OperatingActor} de {@code clinical} desde la sesion y el contexto del request.
 *
 * <p><b>El nombre lleva el prefijo del modulo a proposito.</b> Dos clases {@code ApiActor} en
 * paquetes distintos colisionan por nombre de bean y Spring no arranca — es la leccion que dejo
 * 02.06. {@code resource} y {@code organization} tienen el suyo llamado {@code ApiActor} porque
 * fueron los primeros; todos los posteriores llevan prefijo.
 *
 * <p>Una sesion sin contexto de trabajo produce un actor con {@code contextOrganizationId} y
 * {@code consultorioId} en {@code null}, y <b>no falla aca</b>: quien lo rechaza es
 * {@code AutorizacionClinica}, en un solo lugar y con 403. Fallar en el borde duplicaria esa
 * decision en doce controllers y la haria divergir en cuanto alguno se olvide.
 */
@Component
class ClinicalApiActor {

	private final TenantContextHolder tenantContextHolder;

	ClinicalApiActor(TenantContextHolder tenantContextHolder) {
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
