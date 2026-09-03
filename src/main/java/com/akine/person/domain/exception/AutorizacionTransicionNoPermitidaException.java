package com.akine.person.domain.exception;

/**
 * La autorizacion no admite esa transicion desde su estado actual (409).
 *
 * <p>APROBADA y RECHAZADA son terminales: corregir una decision tomada es dar de baja la
 * autorizacion y cargar otra, no reescribir el estado. Si una autorizacion aprobada pudiera volver
 * a PENDIENTE, el saldo que ya se conto para atender a alguien desapareceria retroactivamente.
 *
 * <p>Es tambien lo que resuelve el caso borde "aprobacion concurrente": el segundo hilo encuentra
 * la autorizacion ya APROBADA y recibe esto, no un 200 que aprueba dos veces.
 */
public class AutorizacionTransicionNoPermitidaException extends RuntimeException {

	private final long autorizacionId;
	private final String estadoActual;
	private final String accion;

	public AutorizacionTransicionNoPermitidaException(
			long autorizacionId, String estadoActual, String accion) {

		super("Una autorizacion " + estadoActual + " no admite la accion " + accion);
		this.autorizacionId = autorizacionId;
		this.estadoActual = estadoActual;
		this.accion = accion;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}

	public String getEstadoActual() {
		return estadoActual;
	}

	public String getAccion() {
		return accion;
	}
}
