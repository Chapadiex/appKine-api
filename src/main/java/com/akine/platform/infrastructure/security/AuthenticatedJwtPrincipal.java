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
record AuthenticatedJwtPrincipal(AccessTokenClaims claims, boolean platformAdmin)
		implements AuthenticatedPrincipal {

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
	 * <p><b>Desde AKINE-01.03 este valor ya no sale del claim {@code rol}.</b> Hasta 01.02 se
	 * comparaba {@code claims.roleCode()} contra {@code "PLATFORM_ADMIN"}, y eso contradecia
	 * frontalmente lo que documenta {@code AccessTokenClaims}: <i>"si alguien empieza a autorizar
	 * por este campo, la ventana de revocacion de permisos deja de ser cero y pasa a ser el TTL
	 * del token... No lo hagas."</i> Era una contradiccion inofensiva solo porque el claim nunca
	 * valia ese valor —y por eso {@code POST /organizations} y las dos operaciones de suscripcion
	 * estaban publicadas en el contrato y no las podia ejecutar nadie—.
	 *
	 * <p>Ahora lo resuelve {@code JwtAuthenticationFilter} consultando
	 * {@code platform.spi.tenant.PlatformRoleDirectory} contra {@code platform_role}, <b>una vez
	 * por request y sin cache</b> (ADR-0020). El claim {@code rol} sigue existiendo como pista
	 * para el frontend, y nadie autoriza con el.
	 */
	@Override
	public boolean platformAdmin() {
		return platformAdmin;
	}
}
