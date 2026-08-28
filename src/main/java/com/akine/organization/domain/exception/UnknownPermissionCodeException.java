package com.akine.organization.domain.exception;

/**
 * El codigo de permiso recibido no esta en el catalogo de la matriz, o no es otorgable como
 * grant en esta fase.
 *
 * <p><b>400 y no 500.</b> Es un error del cliente y tiene que decirlo. Traducirlo con un
 * {@code valueOf} pelado convertiria un dato mal escrito en un error de servidor, y escribir la
 * fila igual dejaria en {@code membership_grant} un permiso que el evaluador nunca va a mirar:
 * un permiso que figura otorgado y no habilita nada es peor que un rechazo.
 *
 * <p>Los codigos otorgables son dos: {@code auditoria:read-clinica} (matriz seccion 6, el "No por
 * defecto (grant)" de F1) y, desde AKINE-03.01, {@code paciente:manage} (matriz seccion 4, el
 * "Segun permiso" del {@code PROFESIONAL}). Los demas codigos del catalogo existen y no son
 * otorgables todavia, que es una respuesta distinta de "no existe" y por eso el mensaje las
 * distingue. La lista vive en {@code RolePermissions.otorgablesComoGrant()}.
 */
public class UnknownPermissionCodeException extends RuntimeException {

	private final String permissionCode;

	public UnknownPermissionCodeException(String permissionCode, String detalle) {
		super(detalle);
		this.permissionCode = permissionCode;
	}

	public String getPermissionCode() {
		return permissionCode;
	}
}
