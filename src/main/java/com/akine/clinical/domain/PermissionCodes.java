package com.akine.clinical.domain;

/**
 * Codigos de permiso que este modulo evalua.
 *
 * <p>Son los mismos literales del catalogo de la matriz de permisos (seccion 5) y de
 * {@code organization.domain.PermissionCode}. Se repiten aca como constantes porque el enum vive
 * en otro modulo y no es parte de su {@code spi}: {@code PermissionGuard} recibe el codigo como
 * {@code String} justamente para que un modulo consumidor no tenga que importar el enum ajeno.
 *
 * <p>El precio es que un cambio de literal rompe en silencio. Lo cubre
 * {@code PermissionEvaluatorService}, que ante un codigo desconocido <b>deniega</b> —fail-closed—
 * en vez de conceder.
 */
public final class PermissionCodes {

	/** Ver Historia Clinica. */
	public static final String HC_READ = "hc:read";

	/** Editar Historia Clinica: resumen y antecedentes. */
	public static final String HC_WRITE = "hc:write";

	private PermissionCodes() {
	}
}
