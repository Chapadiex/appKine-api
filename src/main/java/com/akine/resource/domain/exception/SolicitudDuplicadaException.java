package com.akine.resource.domain.exception;

/**
 * El mismo tenant ya tiene una solicitud PENDIENTE por el mismo concepto (409).
 *
 * <p>Es la idempotencia que CA-M06-005-05 exige, y la sostiene la base: el unique de
 * {@code catalogo_solicitud} incluye una columna generada que vale el nombre propuesto mientras
 * la solicitud esta pendiente y {@code NULL} en cuanto se resuelve. Un reintento por timeout de
 * red choca contra esa fila y responde 409 en vez de crear una segunda solicitud identica que
 * la plataforma tendria que resolver dos veces.
 *
 * <p><b>Volver a pedir algo ya RECHAZADO no produce este error</b>, y es deliberado: un centro
 * puede insistir con argumentos nuevos, y esa segunda solicitud es una fila nueva con su propia
 * justificacion.
 */
public class SolicitudDuplicadaException extends RuntimeException {

	private final String nombrePropuesto;

	public SolicitudDuplicadaException(String nombrePropuesto) {
		super("Ya hay una solicitud pendiente por ese concepto");
		this.nombrePropuesto = nombrePropuesto;
	}

	public String getNombrePropuesto() {
		return nombrePropuesto;
	}
}
