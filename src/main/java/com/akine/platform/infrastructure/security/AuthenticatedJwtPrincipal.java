package com.akine.platform.infrastructure.security;

import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;

/**
 * El principal que publica {@link JwtAuthenticationFilter}, respaldado por los claims del
 * access token.
 *
 * <p><b>Los claims son un hint, nunca autoridad.</b> {@code organizationId} y
 * {@code consultorioId} dicen QUE contexto se esta pidiendo; quien decide si ese contexto es
 * accesible es {@code TenantContextFilter}, revalidando la membership contra la base en cada
 * request (RN-M01-003). Este record no valida nada: transporta.
 *
 * <p>Expone tambien los {@link #claims()} completos porque la capa {@code api} necesita el
 * claim {@code fam} —la familia de la sesion— para pedir un cambio de contexto sin tener la
 * cookie de refresh a mano: la cookie viaja con {@code Path=/api/v1/auth} y no llega al
 * endpoint de seleccion de contexto.
 */
record AuthenticatedJwtPrincipal(AccessTokenClaims claims) implements AuthenticatedPrincipal {

	/**
	 * Rol que, en la matriz aprobada, opera por encima de cualquier tenant.
	 *
	 * <p>Se compara como texto y no contra {@code organization.domain.RoleCode} porque
	 * {@code platform} no puede importar el dominio de otro modulo (ADR-0001).
	 */
	private static final String ROL_ADMIN_PLATAFORMA = "PLATFORM_ADMIN";

	@Override
	public long accountId() {
		return claims.accountId();
	}

	@Override
	public Long organizationId() {
		return claims.scope() == AccessTokenScope.CONTEXT ? claims.organizationId() : null;
	}

	@Override
	public Long consultorioId() {
		return claims.scope() == AccessTokenScope.CONTEXT ? claims.consultorioId() : null;
	}

	/**
	 * Administra la plataforma entera.
	 *
	 * <p>Sale del claim {@code rol}, que a su vez sale de la membership revalidada en el ultimo
	 * cambio de contexto o refresh: <b>no es un dato que el cliente pueda proponer</b>, porque
	 * el token esta firmado y el rol se resuelve del lado del servidor.
	 *
	 * <p><b>TODO(AKINE-01.03):</b> hoy nada emite {@code PLATFORM_ADMIN} —no hay origen de dato
	 * para ese rol fuera de una membership de organizacion—, asi que en la practica esto
	 * devuelve siempre {@code false} y las operaciones de plataforma quedan inalcanzables por
	 * HTTP. Falla cerrado a proposito: la alternativa —inferirlo de otra cosa— seria elevar
	 * privilegios por heuristica. Lo resuelve la matriz de permisos de 01.03, que es la etapa
	 * que define de donde sale ese rol.
	 */
	@Override
	public boolean platformAdmin() {
		return ROL_ADMIN_PLATAFORMA.equals(claims.roleCode());
	}
}
