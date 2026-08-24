package com.akine.organization.domain.exception;

/**
 * El actor tiene contexto valido y NO tiene el permiso pedido sobre un recurso de su propia
 * organizacion.
 *
 * <p><b>Es un 403 y no un 404, y la distincion es deliberada.</b> El 404 existe para no
 * confirmar la existencia de datos de OTRO tenant; sobre un recurso que el actor ya sabe que
 * existe —porque es de su organizacion— no protege nada y ademas le miente: le dice "no
 * existe" cuando lo que pasa es que no puede. La regla completa esta en
 * {@link MembershipNotAccessibleException}, que es la mitad opuesta.
 *
 * <p>Cada rechazo por permiso deja una fila {@code PERMISSION_DENIED} en la auditoria
 * (AGENT.md seccion 10). Los rechazos por ALCANCE no: registrarlos construiria dentro de
 * {@code audit_event} el mismo padron de existencia de tenants ajenos que el 404 uniforme
 * existe para no entregar.
 */
public class PermissionDeniedException extends RuntimeException {

	private final String permissionCode;
	private final long accountId;
	private final Long organizationId;

	public PermissionDeniedException(String permissionCode, long accountId, Long organizationId) {
		super("El actor no tiene el permiso requerido para esta operacion");
		this.permissionCode = permissionCode;
		this.accountId = accountId;
		this.organizationId = organizationId;
	}

	/** Permiso que falto. Va al log y al cuerpo: el actor puede usarlo para pedir acceso. */
	public String getPermissionCode() {
		return permissionCode;
	}

	public long getAccountId() {
		return accountId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}
}
