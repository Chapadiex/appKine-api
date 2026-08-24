package com.akine.organization.api;

/**
 * El request llego sin contexto de trabajo a un endpoint que lo exige.
 *
 * <h2>Por que hace falta un tipo propio y no alcanza {@code AccessDeniedException}</h2>
 *
 * <p>Las dos terminan en 403, pero el {@code type} del Problem Details no es el mismo y el
 * frontend <b>ramifica por ese campo</b>: {@code forbidden} significa "no tenes permiso" y
 * muestra un mensaje; {@code missing-tenant-context} significa "elegi donde trabajas" y lleva al
 * selector de contexto. Mandar el primero donde corresponde el segundo deja al usuario mirando
 * un error que no puede resolver.
 *
 * <p>{@code TenantContextFilter} ya emite este mismo {@code type} para la mayoria de los
 * requests sin contexto, y por eso casi ningun controller necesita esta excepcion. El caso que
 * la necesita es el {@code PLATFORM_ADMIN}: el filtro lo deja pasar sin contexto a proposito
 * —opera por encima de los tenants y exigirselo haria imposible el alta de organizaciones—, asi
 * que un endpoint que si necesita tenant tiene que decirlo por su cuenta y con el mismo
 * vocabulario.
 *
 * <p><b>Nunca 401.</b> El interceptor del frontend borra el token ante cualquier 401 y arranca
 * un bucle de login.
 */
public class MissingTenantContextException extends RuntimeException {

	public MissingTenantContextException(String message) {
		super(message);
	}
}
