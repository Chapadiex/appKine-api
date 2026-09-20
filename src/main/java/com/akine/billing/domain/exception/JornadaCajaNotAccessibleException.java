package com.akine.billing.domain.exception;

/**
 * La jornada no existe, o es de otro tenant o de otra sede. <b>404</b>.
 *
 * <p>404 y nunca 403: un 403 confirma que la fila existe, y bastaria recorrer ids para saber
 * cuantas cajas opera otro centro del SaaS. Regla fijada en 01.01.
 */
public class JornadaCajaNotAccessibleException extends RuntimeException {

	private final long jornadaId;

	public JornadaCajaNotAccessibleException(long jornadaId) {
		super("La jornada de caja " + jornadaId + " no existe en este alcance");
		this.jornadaId = jornadaId;
	}

	public long getJornadaId() {
		return jornadaId;
	}
}
