package com.akine.person.domain.exception;

/**
 * El adjunto no existe, es de otra organizacion, o es de otra persona (404).
 *
 * <p>Los tres casos colapsan en el mismo error a proposito, mismo criterio que
 * {@link PersonaNotAccessibleException}: distinguirlos confirmaria que ese id existe en algun
 * lado, y bastaria recorrer numeros para medir cuantos documentos guarda otro centro.
 */
public class AdjuntoNotAccessibleException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long adjuntoId;

	public AdjuntoNotAccessibleException(long adjuntoId) {
		super("Adjunto no accesible: " + adjuntoId);
		this.adjuntoId = adjuntoId;
	}

	public long getAdjuntoId() {
		return adjuntoId;
	}
}
