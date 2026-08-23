package com.akine.identity.api;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Quien hace el request, traducido a lo que los servicios de {@code identity} esperan recibir.
 *
 * <p>Es el gemelo de {@code organization.api.ApiActor} y copia su criterio a proposito, en vez
 * de compartir la clase: {@code ApiActor} es package-private de {@code organization.api} y
 * hacerlo publico para reusarlo desde aca abriria una puerta entre las capas HTTP de dos
 * modulos, que es exactamente lo que ADR-0001 cierra. Duplicar veinte lineas cuesta menos que
 * ese acoplamiento.
 *
 * <p><b>De donde sale cada cosa.</b> {@code accountId} y {@code platformAdmin} vienen del
 * principal autenticado que publico el filtro JWT. {@code contextOrganizationId} viene del
 * contexto que {@code TenantContextFilter} ya revalido contra la base, NUNCA de los claims del
 * token ni de un parametro del cliente: los claims dicen que contexto se pide, no cual es
 * legitimo (RN-M01-003).
 *
 * <p><b>Sin principal es 403, jamas 401.</b> Regla heredada de 01.01: el interceptor del
 * frontend borra el token ante cualquier 401, asi que un 401 emitido desde un endpoint de
 * negocio deja al usuario en un bucle de login. Los 401 de este modulo salen unicamente de los
 * endpoints de sesion —credenciales o refresh invalidos—, que es donde significan algo.
 *
 * @param accountId             cuenta autenticada que opera
 * @param platformAdmin         administra la plataforma, por encima de cualquier tenant
 * @param contextOrganizationId organizacion del contexto validado, o {@code null} si el request
 *                              no trae contexto
 */
record IdentityApiActor(long accountId, boolean platformAdmin, Long contextOrganizationId) {

	/**
	 * Resuelve el actor del request en curso.
	 *
	 * @throws AccessDeniedException si no hay principal de AKINE en el contexto de seguridad
	 */
	static IdentityApiActor current(TenantContextHolder tenantContextHolder) {
		AuthenticatedPrincipal principal = principalAutenticado();
		if (principal == null) {
			throw new AccessDeniedException("La operacion requiere una sesion autenticada");
		}
		Long organizacionDelContexto = tenantContextHolder.current()
				.map(RequestTenantContext::organizationId)
				.orElse(null);
		return new IdentityApiActor(
				principal.accountId(), principal.platformAdmin(), organizacionDelContexto);
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
