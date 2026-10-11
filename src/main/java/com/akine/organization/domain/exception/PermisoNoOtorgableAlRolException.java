package com.akine.organization.domain.exception;

/**
 * El permiso existe en el catalogo, pero la matriz no admite otorgarlo como grant al rol de la
 * membership ({@code RolePermissions.otorgableComoGrant}).
 *
 * <p><b>Es otra respuesta que un codigo inexistente, y por eso otra excepcion.</b> Antes del
 * contrato 0.84.0 las dos salian como {@code validation-error} con el motivo en el
 * {@code detail}, que es prosa: la pantalla de colaboradores no tenia como distinguir "escribiste
 * mal el codigo" de "a este rol no se le puede dar". Ahora sale como
 * {@code permiso-no-otorgable-al-rol} con el rol y el permiso en propiedades.
 *
 * <p>Cubre tanto la celda "No" de la matriz (§3: <i>"No otorgable por membership"</i>) como el
 * permiso del catalogo que no es otorgable a ningun rol: para quien otorga, los dos son "a este
 * rol no", y el endpoint {@code permisos-otorgables} ya le dice cuales si.
 */
public class PermisoNoOtorgableAlRolException extends RuntimeException {

	private final String roleCode;
	private final String permissionCode;

	public PermisoNoOtorgableAlRolException(String roleCode, String permissionCode) {
		super("La matriz de permisos no admite otorgar " + permissionCode + " al rol " + roleCode);
		this.roleCode = roleCode;
		this.permissionCode = permissionCode;
	}

	public String getRoleCode() {
		return roleCode;
	}

	public String getPermissionCode() {
		return permissionCode;
	}
}
