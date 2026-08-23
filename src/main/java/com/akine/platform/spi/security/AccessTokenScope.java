package com.akine.platform.spi.security;

/**
 * Alcance de un access token: si la persona ya eligio donde trabaja o todavia no.
 *
 * <p>Existe porque el login y la seleccion de contexto son dos momentos distintos (ADR-0009).
 * Entre uno y otro hay un token legitimo que identifica a la persona pero no habilita ninguna
 * operacion de negocio: sin organizacion ni consultorio, no hay tenant contra el cual filtrar
 * una consulta, y una consulta sin tenant es exactamente la fuga que el multi-tenant existe
 * para evitar.
 *
 * <p><b>No hace falta un filtro que prohiba {@link #PRE_CONTEXT} en los endpoints de negocio.</b>
 * Un token pre-contexto no lleva {@code org} ni {@code loc}, asi que
 * {@code TenantContextFilter} lo rechaza con {@code 403 missing-tenant-context} antes de
 * llegar al controller. La regla vive en un solo lugar y no en dos que puedan divergir.
 */
public enum AccessTokenScope {

	/** Autenticado, sin contexto elegido. Solo sirve para listar contextos, elegir uno y salir. */
	PRE_CONTEXT,

	/** Autenticado y acotado a una organizacion y un consultorio. */
	CONTEXT;

	/** Valor tal como viaja en el claim {@code scope} del JWT. */
	public String claimValue() {
		return this == PRE_CONTEXT ? "pre_context" : "context";
	}

	/**
	 * Traduce el valor del claim, o {@code null} si no corresponde a ningun alcance conocido.
	 *
	 * <p>Devuelve {@code null} en vez de lanzar porque quien llama es el verificador de un
	 * token que puede venir de cualquiera: un valor desconocido es un token invalido, no un
	 * error del servidor.
	 */
	public static AccessTokenScope fromClaim(String value) {
		if (PRE_CONTEXT.claimValue().equals(value)) {
			return PRE_CONTEXT;
		}
		if (CONTEXT.claimValue().equals(value)) {
			return CONTEXT;
		}
		return null;
	}
}
