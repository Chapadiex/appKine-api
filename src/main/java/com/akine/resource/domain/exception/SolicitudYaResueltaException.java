package com.akine.resource.domain.exception;

/**
 * Se intento resolver una solicitud que ya estaba aprobada o rechazada (409).
 *
 * <p>La resolucion es terminal. 409 y no un 200 silencioso porque dos administradores de
 * plataforma trabajando sobre la misma bandeja tienen que enterarse de que el otro llego
 * primero: aprobar sobre un rechazo ajeno, sin aviso, dejaria la decision anterior sin rastro.
 */
public class SolicitudYaResueltaException extends RuntimeException {

	private final long solicitudId;

	public SolicitudYaResueltaException(long solicitudId) {
		super("La solicitud " + solicitudId + " ya fue resuelta");
		this.solicitudId = solicitudId;
	}

	public long getSolicitudId() {
		return solicitudId;
	}
}
