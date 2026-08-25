package com.akine.resource.domain.exception;

/**
 * La solicitud no existe, o es de otro tenant (404).
 *
 * <p>Mismo criterio que el resto del modulo (ADR-0018): un 403 confirmaria que ese id existe y
 * bastaria recorrer numeros para saber cuantas solicitudes tiene abiertas cada centro del SaaS.
 */
public class SolicitudNotAccessibleException extends RuntimeException {

	private final long solicitudId;

	public SolicitudNotAccessibleException(long solicitudId) {
		super("Solicitud de catalogo no accesible: " + solicitudId);
		this.solicitudId = solicitudId;
	}

	public long getSolicitudId() {
		return solicitudId;
	}
}
