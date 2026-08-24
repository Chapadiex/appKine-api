package com.akine.organization.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Lo que la capa {@code api} sabe de quien hace el request, listo para el guard de autorizacion.
 *
 * <p><b>Por que existe.</b> {@code AuthorizationGuard} vive en {@code application} y
 * recibe primitivos: no puede conocer HTTP, ni Spring Security, ni la forma del token —que
 * ademas todavia no existe, la define 01.02. Traducir "el request en curso" a
 * {@code (accountId, platformAdmin, contextOrganizationId)} es trabajo de esta capa, y tenerlo
 * en un solo lugar evita que cada controller lo resuelva a su manera.
 *
 * <p><b>De donde sale cada cosa.</b> {@code accountId} y {@code platformAdmin} vienen del
 * principal autenticado. {@code contextOrganizationId} viene del contexto que
 * {@code TenantContextFilter} ya revalido contra la base, NUNCA de los claims del token ni de
 * un parametro del cliente: los claims dicen que contexto se pide, no cual es legitimo
 * (RN-M01-003).
 *
 * <p><b>Sin principal es 403, jamas 401.</b> No es una preferencia: el interceptor del frontend
 * borra el token ante cualquier 401, asi que un 401 emitido desde un endpoint de negocio deja
 * al usuario en un bucle de login (B-2). Ademas, decidir "no se quien sos" es trabajo del punto
 * de entrada de autenticacion que llega en 01.02, no de un controller.
 *
 * @param accountId               cuenta autenticada que opera
 * @param platformAdmin           administra la plataforma, por encima de cualquier tenant
 * @param contextOrganizationId   organizacion del contexto validado, o {@code null} si el
 *                                request no trae contexto (caso normal de un PLATFORM_ADMIN)
 */
record ApiActor(long accountId, boolean platformAdmin, Long contextOrganizationId) {

	/**
	 * Resuelve el actor del request en curso.
	 *
	 * @throws AccessDeniedException si no hay principal de AKINE en el contexto de seguridad
	 */
	static ApiActor current(TenantContextHolder tenantContextHolder) {
		AuthenticatedPrincipal principal = principalAutenticado();
		if (principal == null) {
			throw new AccessDeniedException("La operacion requiere una sesion autenticada");
		}
		Long organizacionDelContexto = tenantContextHolder.current()
				.map(RequestTenantContext::organizationId)
				.orElse(null);
		return new ApiActor(principal.accountId(), principal.platformAdmin(), organizacionDelContexto);
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
