package com.akine.resource.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.resource.application.OperatingActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Traduce "el request en curso" al {@link OperatingActor} que espera {@code application}.
 *
 * <p><b>Por que es un bean y no un record estatico como el de {@code organization}.</b> Aquel
 * recibe el {@code TenantContextHolder} por parametro en cada llamada porque lo tiene a mano el
 * controller. Aca el controller no lo necesita para nada mas, y pasarselo solo para reenviarlo
 * es ruido: se inyecta una vez, en esta clase, que es la unica que lo usa.
 *
 * <p><b>De donde sale cada cosa, y por que la diferencia importa.</b> {@code accountId} y
 * {@code platformAdmin} vienen del principal autenticado —el segundo revalidado contra
 * {@code platform_role} en este request, no de un claim—. Los dos ids del contexto vienen de
 * {@code TenantContextFilter}, que ya los revalido contra la base: <b>nunca de los claims del
 * token ni de un parametro del cliente</b>. Los claims dicen que contexto se pide, no cual es
 * legitimo (RN-M01-003).
 *
 * <p><b>Sin principal es 403, jamas 401.</b> No es una preferencia: el interceptor del frontend
 * borra el token ante cualquier 401, asi que un 401 emitido desde un endpoint de negocio deja
 * al usuario en un bucle de login del que no sale.
 */
@Component
class ApiActor {

	private final TenantContextHolder tenantContextHolder;

	ApiActor(TenantContextHolder tenantContextHolder) {
		this.tenantContextHolder = tenantContextHolder;
	}

	/**
	 * @throws AccessDeniedException si no hay principal de AKINE en el contexto de seguridad
	 */
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

	/**
	 * Principal de AKINE del contexto de seguridad, o {@code null}.
	 *
	 * <p>Se comprueba por tipo y no por "esta autenticado": el token anonimo de Spring Security
	 * se declara autenticado y su principal es un {@code String}, que no sabe responder
	 * {@code accountId()}. Lo unico que sirve es un principal de AKINE.
	 */
	private static AuthenticatedPrincipal principalAutenticado() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		Object principal = authentication.getPrincipal();
		return principal instanceof AuthenticatedPrincipal akine ? akine : null;
	}
}
