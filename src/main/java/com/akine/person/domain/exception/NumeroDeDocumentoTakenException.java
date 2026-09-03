package com.akine.person.domain.exception;

/**
 * El numero de esa orden o autorizacion ya esta cargado y vigente (409).
 *
 * <p>Lo hace cumplir un unique de la base y no la aplicacion, porque aca la regla SI es una
 * igualdad: {@code (organization_id, persona_id, numero)} para la orden y
 * {@code (organization_id, cobertura_id, numero)} para la autorizacion. Es la otra mitad de la
 * leccion del Paquete B — donde la regla es una igualdad el unique sirve, y solo el solapamiento
 * de intervalos necesita un lock.
 *
 * <p>Lleva {@code deleted_key}: el numero de un documento dado de baja se puede volver a cargar.
 */
public class NumeroDeDocumentoTakenException extends RuntimeException {

	private final String documento;
	private final String numero;

	public NumeroDeDocumentoTakenException(String documento, String numero) {
		super("Ya hay una " + documento + " vigente con el numero " + numero);
		this.documento = documento;
		this.numero = numero;
	}

	public String getDocumento() {
		return documento;
	}

	public String getNumero() {
		return numero;
	}
}
