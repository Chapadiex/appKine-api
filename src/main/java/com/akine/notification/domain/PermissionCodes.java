package com.akine.notification.domain;

/**
 * Los permisos que {@code notification} pide, como texto.
 *
 * <p>Texto y no {@code organization.domain.PermissionCode}: ese enum vive en el dominio de otro
 * modulo y ArchUnit no deja alcanzarlo. Mismo criterio que el resto de los modulos.
 */
public final class PermissionCodes {

	/**
	 * Reintentar una notificacion del tenant (A-3).
	 *
	 * <p>Reusa {@code colaborador:manage} en vez de abrir un codigo propio: hoy las unicas
	 * notificaciones con {@code organization_id} son las invitaciones, que ya gobierna ese
	 * permiso, y lo tienen exactamente los actores que deben poder reintentarlas. Si mas adelante
	 * se encolan recordatorios de turno (E-5), la decision se revisa.
	 */
	public static final String NOTIFICACION_RETRY = "colaborador:manage";

	private PermissionCodes() {
	}
}
