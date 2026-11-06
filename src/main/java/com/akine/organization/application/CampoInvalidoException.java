package com.akine.organization.application;

/**
 * Un campo del pedido no pasa una regla que Bean Validation no puede evaluar sola (400).
 *
 * <p>Se responde con la MISMA forma que un {@code @Valid} rechazado: {@code validation-error} con
 * el campo y su mensaje en {@code errors}, para que el formulario lo marque como a cualquier otro.
 * Es el caso del CUIT —el digito verificador se calcula— y de la ubicacion a medias —latitud sin
 * longitud—, que una anotacion por campo no ve.
 */
public class CampoInvalidoException extends RuntimeException {

	private final String campo;

	public CampoInvalidoException(String campo, String mensaje) {
		super(mensaje);
		this.campo = campo;
	}

	public String getCampo() {
		return campo;
	}
}
